extern crate landlord;

use landlord::args::*;
use landlord::bindings::*;
use std::io::prelude::*;
use std::net::TcpStream;
use std::os::unix::net::UnixStream;
use std::sync::mpsc::*;
use std::{env, io, process, str, time};

const RETRY_DELAY_MILLIS: u64 = 5000;
const VERSION: &'static str = env!("CARGO_PKG_VERSION");

const USAGE: &'static str = "Usage: landlord [-options] class [args...]
           (to execute a class)
   or  landlord [-options] -jar jarfile [args...]
           (to execute a jar file)
where options include:
    -cp <class search path of directories and zip/jar files> -classpath <class search path of directories and zip/jar files>
                  A : separated list of directories, JAR archives,
                  and ZIP archives to search for class files.
    -D<name>=<value>
                  set a system property
    -version      print product version and exit
    -showversion  print product version and continue
    -? -help      print this help message
    -host | -H    host to connect to. available schemes: \"unix\", \"tcp\"
    -wait         if provided, wait until landlordd is ready before connecting";

fn main() {
    let args: Vec<String> = env::args().collect();
    let parsed = parse_java_args(&args[1..]);

    if parsed.version {
        eprintln!("landlord version \"{}\"", VERSION);
    }

    if parsed.errors.is_empty() {
        match parsed.mode {
            ExecutionMode::Class {
                ref class,
                ref args,
            } => match parsed.host {
                Host::Unix(path) => {
                    handle_execute_class(
                        parsed.cp.as_slice(),
                        class,
                        args,
                        parsed.props.as_slice(),
                        parsed.wait,
                        || UnixStream::connect(&path),
                    );
                }

                Host::Tcp(address) => {
                    handle_execute_class(
                        parsed.cp.as_slice(),
                        class,
                        args,
                        parsed.props.as_slice(),
                        parsed.wait,
                        || TcpStream::connect(&address),
                    );
                }
            },

            ExecutionMode::Exit { code } => {
                process::exit(code);
            }

            ExecutionMode::Help { code } => {
                eprintln!("{}", USAGE);

                process::exit(code);
            }

            ExecutionMode::JarFile {
                file: _file,
                args: _args,
            } => {
                eprintln!("landlord: `-jar` currently unsupported");

                process::exit(1);
            }
        }
    } else {
        parsed
            .errors
            .iter()
            .for_each(|e| println!("landlord: {}", e));

        process::exit(1);
    }
}

fn handle_execute_class<IO, NewS, S>(
    cp: &[S],
    class: &S,
    args: &[S],
    props: &[(S, S)],
    wait: bool,
    mut new_stream: NewS,
) -> ()
where
    IO: IOStream + Read + Send + Write + 'static,
    NewS: FnMut() -> io::Result<IO>,
    S: AsRef<str>,
{
    if wait {
        wait_until_ready(
            &mut new_stream,
            time::Duration::from_millis(RETRY_DELAY_MILLIS),
        )
    }

    match new_stream() {
        Err(ref mut e) => {
            eprintln!("landlord: failed to connect: {:?}", e);

            process::exit(1);
        }

        Ok(mut stream) => {
            let (tx, rx) = channel();

            let result = install_fs_and_start(cp, props, class, args, &mut stream)
                .and_then(|pid| stream.try_clone().map(|stream_writer| (pid, stream_writer)))
                .and_then(|(pid, mut stream_writer)| {
                    spawn_and_handle_signals(tx.clone());
                    spawn_and_handle_stdin(tx.clone());
                    spawn_and_handle_stream_read(stream, tx.clone());

                    handle_events(pid, &mut stream_writer, rx, new_stream)
                });

            let code = match result {
                Ok(c) => c,

                Err(e) => {
                    eprintln!("landlord: {:?}", e);
                    1
                }
            };

            process::exit(code);
        }
    }
}
