use chan_signal::{notify, Signal};
use libc;
use proto::*;
use std::{fs, io, marker, net, path, process, thread};
use std::net::TcpStream;
use std::io::prelude::*;
use std::os::unix::net::UnixStream;
use std::sync::mpsc::*;
use tar::Builder;

/// Binds everything together and ensures that events received from a given `reader` will
/// be handled accordingly.
pub fn handle_events<NewS, IO>(
    pid: i32,
    socket: &mut IO,
    reader: Receiver<Input>,
    mut new_socket: NewS,
) -> io::Result<i32>
where
    NewS: FnMut() -> io::Result<IO>,
    IO: IOSocket + Read + Write
{
    let mut stdout = io::stdout();
    let mut stderr = io::stderr();

    let handler_reader = || {
        reader
            .recv()
            .map_err(|e| io::Error::new(io::ErrorKind::BrokenPipe, e))
    };
    let handler_writer = |bs: Vec<u8>| {
        if bs.is_empty() {
            socket.shutdown(net::Shutdown::Write)
        } else {
            socket.write_all(&bs)
        }

    };
    let session_writer = |bs: Vec<u8>| new_socket().and_then(|ref mut s| s.write_all(&bs));
    let std_out = |bs: Vec<u8>| stdout.write_all(&bs);
    let std_err = |bs: Vec<u8>| stderr.write_all(&bs);

    input_handler(
        pid,
        handler_reader,
        handler_writer,
        session_writer,
        std_out,
        std_err,
    )
}

/// Signals to the OS which signals we are interested in, and then
/// spawns a thread to wait for them and forward them to the
/// provided `sender`.
pub fn spawn_and_handle_signals(sender: Sender<Input>) {
    let all_signals = [
        Signal::ABRT,
        Signal::ALRM,
        Signal::BUS,
        Signal::CHLD,
        Signal::CONT,
        Signal::FPE,
        Signal::HUP,
        Signal::ILL,
        Signal::INT,
        Signal::IO,
        Signal::KILL,
        Signal::PIPE,
        Signal::PROF,
        Signal::QUIT,
        Signal::SEGV,
        Signal::STOP,
        Signal::SYS,
        Signal::TERM,
        Signal::TRAP,
        Signal::TSTP,
        Signal::TTIN,
        Signal::TTOU,
        Signal::URG,
        Signal::USR1,
        Signal::USR2,
        Signal::VTALRM,
        Signal::WINCH,
        Signal::XCPU,
        Signal::XFSZ,
    ];

    // chan_signal doesn't have a public function for converting a signal to its integer code
    // so we have to do that ourselves..

    let as_sig = |s: &Signal| match *s {
        Signal::HUP => libc::SIGHUP,
        Signal::INT => libc::SIGINT,
        Signal::QUIT => libc::SIGQUIT,
        Signal::ILL => libc::SIGILL,
        Signal::ABRT => libc::SIGABRT,
        Signal::FPE => libc::SIGFPE,
        Signal::KILL => libc::SIGKILL,
        Signal::SEGV => libc::SIGSEGV,
        Signal::PIPE => libc::SIGPIPE,
        Signal::ALRM => libc::SIGALRM,
        Signal::TERM => libc::SIGTERM,
        Signal::USR1 => libc::SIGUSR1,
        Signal::USR2 => libc::SIGUSR2,
        Signal::CHLD => libc::SIGCHLD,
        Signal::CONT => libc::SIGCONT,
        Signal::STOP => libc::SIGSTOP,
        Signal::TSTP => libc::SIGTSTP,
        Signal::TTIN => libc::SIGTTIN,
        Signal::TTOU => libc::SIGTTOU,
        Signal::BUS => libc::SIGBUS,
        Signal::PROF => libc::SIGPROF,
        Signal::SYS => libc::SIGSYS,
        Signal::TRAP => libc::SIGTRAP,
        Signal::URG => libc::SIGURG,
        Signal::VTALRM => libc::SIGVTALRM,
        Signal::XCPU => libc::SIGXCPU,
        Signal::XFSZ => libc::SIGXFSZ,
        Signal::IO => libc::SIGIO,
        Signal::WINCH => libc::SIGWINCH,
        _ => 1,
    };

    let signal = notify(&all_signals);

    thread::spawn(move || loop {
        if let Some(s) = signal.recv() {
            if let Err(e) = sender.send(Input::Signal(as_sig(&s))) {
                eprintln!("landlord: signal handler crashed, {:?}", e);
                return;
            }
        }
    });
}

/// Spawns a thread and consumes stdin, forwarding a copy of
/// the consumed data to provided `sender`
pub fn spawn_and_handle_stdin(sender: Sender<Input>) {
    thread::spawn(move || {
        let stdin = io::stdin();
        let mut stdin_lock = stdin.lock();
        let mut buffer = vec![0; 1024];

        loop {
            let result = stdin_lock.read(&mut buffer).and_then(|num| {
                buffer.truncate(num);

                let (closed, message) = if num == 0 {
                    (true, Input::StdInClosed)
                } else {
                    (false, Input::StdIn(buffer.clone()))
                };

                sender
                    .send(message)
                    .map_err(|e| io::Error::new(io::ErrorKind::BrokenPipe, format!("{:?}", e)))
                    .map(|_| closed)
            });

            match result {
                Ok(closed) if closed => {
                    return;
                }
                Ok(_) => (),
                Err(ref err) if err.kind() == io::ErrorKind::Interrupted => (),
                Err(e) => {
                    eprintln!("landlord: stdin crashed, {:?}", e);
                    return;
                }
            }
        }
    });
}

/// Spawns a thread and reads data from the provided `stream`. The actual logic
/// of how much to read is done via the read_handler function.
pub fn spawn_and_handle_stream_read<IO>(mut socket: IO, sender: Sender<Input>) where IO: IOSocket + Read + Send + Write + 'static {
    thread::spawn(move || {
        let s = &mut socket;
        let r = |n: usize| read_bytes(s, n);
        let m = |msg: Input| {
            sender
                .send(msg)
                .map_err(|e| io::Error::new(io::ErrorKind::BrokenPipe, e))
        };

        if let Err(read_error) = read_handler(r, m) {
            if let Err(_send_error) = sender.send(Input::Fail(read_error)) {
                eprintln!("landlord: catastrophic failure (channel and read crashed)");
                process::exit(1);
            }
        }
    });
}

/// Writes the provided `class_path` to the provided `stream` and starts the process. Returns
/// the process id (from landlordd's perpsective). Upon successful completion, the process
/// is running and any data subsequently written to `stream` is stdin.
pub fn install_fs_and_start<IO>(
    class_path: &Vec<String>,
    props: &Vec<(String, String)>,
    class: &String,
    args: &Vec<String>,
    socket: &mut IO,
) -> io::Result<i32> where IO: IOSocket + Read + Write {
    // given a list of class path entries, these are written to the tar via their position in
    // the vector. Meaning the first entry will be named "0", second "1", and so on. This
    // allows the user to specify any combination of directories and files without us having
    // to find some common parent path string.

    let cp_with_names = class_path_with_names(&class_path);
    let descriptor = app_cmdline(&cp_with_names, &props, &class, &args);

    socket.write_all(descriptor.as_bytes()).and_then(|_| {
        let tar_padding_writer = BlockSizeWriter::new(socket, 10240);

        let mut tar_builder = Builder::new(tar_padding_writer);

        cp_with_names
            .iter()
            .fold(Ok(()), |accum, &(ref path, ref name)| {
                accum.and_then(|_| {
                    fs::canonicalize(path).and_then(|path| {
                        let path_struct = path::Path::new(&path);

                        if path_struct.is_file() {
                            fs::File::open(path_struct)
                                .and_then(|ref mut f| tar_builder.append_file(name, f))
                        } else if path_struct.is_dir() {
                            tar_builder.append_dir_all(name, path.clone())
                        } else {
                            Ok(())
                        }
                    })
                })
            })
            .and_then(|_| {
                tar_builder
                    .finish()
                    .and(tar_builder.into_inner())
                    .and_then(|ref mut socket| socket.finish())
                    .and_then(|ref mut socket| match socket {
                        &mut None => Err(io::Error::new(
                            io::ErrorKind::InvalidInput,
                            "Unable to acquire socket (was finish() called?)",
                        )),

                        &mut Some(ref mut socket) => read_pid_handler(socket).ok_or(
                            io::Error::new(io::ErrorKind::InvalidInput, "Unable to parse pid"),
                        ),
                    })
            })
    })
}

/// BlockSizeWritter ensures that data written to a provided `stream`
/// is done in zero-padded blocks of the provided size. landlordd
/// expects GNU-standard blocking factor of 20, so when writing tar
/// data to it, `landlord` uses this wrapper with a `block_size` of
/// 10240
struct BlockSizeWriter<W: Write> {
    stream: Option<W>,
    written: usize,
    block_size: usize,
}

impl<W: Write> Write for BlockSizeWriter<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self.stream {
            None => Err(io::Error::new(io::ErrorKind::Other, "stream closed")),
            Some(ref mut s) => match s.write(buf) {
                Ok(size) => {
                    self.written += size;

                    Ok(size)
                }

                Err(err) => Err(err),
            },
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        match self.stream {
            None => Err(io::Error::new(io::ErrorKind::Other, "stream closed")),
            Some(ref mut s) => s.flush(),
        }
    }
}

impl<W: Write> BlockSizeWriter<W> {
    pub fn new(obj: W, block_size: usize) -> BlockSizeWriter<W> {
        BlockSizeWriter {
            stream: Some(obj),
            written: 0,
            block_size,
        }
    }

    pub fn finish(&mut self) -> io::Result<Option<W>> {
        let operation = if let Some(ref mut stream) = self.stream {
            let bytes_left = self.block_size - (self.written % self.block_size);
            let bytes = vec![0; bytes_left];

            stream.write_all(&bytes).and(stream.flush())
        } else {
            Ok(())
        };

        operation.map(|_| self.stream.take())
    }
}

/// Exposes underlying shutdown and try_clone functions
/// for the types of sockets we support, i.e. UDS and TCP.
pub trait IOSocket where Self: marker::Sized {
    fn shutdown(&self, how: net::Shutdown) -> io::Result<()>;
    fn try_clone(&self) -> io::Result<Self>;
}

impl IOSocket for UnixStream {
    fn shutdown(&self, how: net::Shutdown) -> io::Result<()> {
        self.shutdown(how)
    }

    fn try_clone(&self) -> io::Result<Self> {
        self.try_clone()
    }
}

impl IOSocket for TcpStream {
    fn shutdown(&self, how: net::Shutdown) -> io::Result<()> {
        self.shutdown(how)
    }

    fn try_clone(&self) -> io::Result<Self> {
        self.try_clone()
    }
}
