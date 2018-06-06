extern crate landlord;

use landlord::args::*;
use landlord::bindings::*;
use std::{env, io, process, str};
use std::io::prelude::*;
use std::net::TcpStream;
use std::os::unix::net::UnixStream;
use std::sync::mpsc::*;

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
    -socket       socket to connect to. available schemes: \"unix\", \"tcp\"";

fn main() {
    let args: Vec<String> = env::args().collect();
    let parsed = parse_java_args(&args[1..].to_vec());

    if parsed.version {
        eprintln!("landlord version \"{}\"", VERSION);
    }

    if parsed.errors.is_empty() {
        match parsed.mode {
            ExecutionMode::Class {
                ref class,
                ref args,
            } => {
                match parsed.socket {
                    Socket::Unix(path)   => {
                        handle_execute_class(&parsed.cp, class, args, &parsed.props, || UnixStream::connect(&path))
                    }

                    Socket::Tcp(address) => {
                        handle_execute_class(&parsed.cp, class, args, &parsed.props, || TcpStream::connect(&address))
                    }
                }
            }

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

fn handle_execute_class<IO, NewS>(cp: &Vec<String>, class: &String, args: &Vec<String>, props: &Vec<(String, String)>, mut new_socket: NewS)
where
    IO: IOSocket + Read + Send + Write + 'static,
    NewS: FnMut() -> io::Result<IO> {

    match new_socket() {
        Err(ref mut e) => {
            eprintln!("landlord: failed to connect to socket: {:?}", e);

            process::exit(1);
        }

        Ok(mut stream) => {
            let (tx, rx) = channel();

            let result = install_fs_and_start(&cp, &props, class, args, &mut stream)
                .and_then(|pid| stream.try_clone().map(|stream_writer| (pid, stream_writer)))
                .and_then(|(pid, mut stream_writer)| {
                    spawn_and_handle_signals(tx.clone());
                    spawn_and_handle_stdin(tx.clone());
                    spawn_and_handle_stream_read(stream, tx.clone());

                    handle_events(pid, &mut stream_writer, rx, new_socket)
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
