extern crate landlord;
extern crate trust_dns_resolver;

use landlord::args::*;
use landlord::bindings::*;
use std::io::prelude::*;
use std::net::TcpStream;
use std::os::unix::net::UnixStream;
use std::sync::mpsc::*;
use std::{env, io, process, str, time};
use trust_dns_resolver::Resolver;

const CARGO_VERSION: &str = env!("CARGO_PKG_VERSION");
const JAVA_OPTS: &str = "JAVA_OPTS";
const RETRY_DELAY_MILLIS: u64 = 1000;
const RELEASE_VERSION: Option<&'static str> = option_env!("RELEASE_VERSION");

const USAGE: &str = "Usage: landlord [-options] class [args...]
           (to execute a class)
   or  landlord [-options] -jar jarfile [args...]
           (to execute a jar file)
where options include:
    -cp <class search path of directories and zip/jar files>
    -classpath <class search path of directories and zip/jar files>
                  A : separated list of directories, JAR archives,
                  and ZIP archives to search for class files.
    -D<name>=<value>
                  set a system property
    -version      print product version and exit
    -showversion  print product version and continue
    -? -help      print this help message
    -host | -H    host to connect to
                  available schemes: \"unix\", \"tcp\"
    -ready        define a readiness check
                  available checks: \"landlordd\", \"tcp://<host>:<port>\"\
    -wait         wait for readiness checks to be successful before connecting
    -wait-time    optional duration of time (milliseconds) to wait
                  for readiness checks to succeed";

fn main() {
    let args: Vec<String> = [
        env::var(JAVA_OPTS).map(parse_java_opts).unwrap_or_default(),
        env::args().skip(1).collect(),
    ]
        .concat();

    let parsed = parse_java_args(&args);

    if parsed.version {
        let version = RELEASE_VERSION.unwrap_or_else(|| CARGO_VERSION);

        eprintln!("landlord version \"{}\"", version);
    }

    let resolver = match Resolver::from_system_conf() {
        Ok(resolver) => resolver,
        Err(e) => {
            eprintln!("landlord: cannot create resolver, {}", e);

            process::exit(1);
        }
    };

    if parsed.errors.is_empty() {
        match parsed.mode {
            ExecutionMode::Class {
                ref class,
                ref args,
            } => match parsed.host {
                Host::Unix(path) => {
                    handle_execute_class(
                        &resolver,
                        parsed.cp.as_slice(),
                        class,
                        args,
                        parsed.props.as_slice(),
                        parsed.readiness_checks.as_slice(),
                        parsed.wait,
                        parsed.wait_time,
                        || UnixStream::connect(&path),
                    );
                }

                Host::Tcp(address) => {
                    handle_execute_class(
                        &resolver,
                        parsed.cp.as_slice(),
                        class,
                        args,
                        parsed.props.as_slice(),
                        parsed.readiness_checks.as_slice(),
                        parsed.wait,
                        parsed.wait_time,
                        || resolve_address(&resolver, &address).and_then(TcpStream::connect),
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
    resolver: &Resolver,
    cp: &[S],
    class: &S,
    args: &[S],
    props: &[(S, S)],
    readiness_checks: &[WaitTarget],
    wait: bool,
    wait_time: Option<time::Duration>,
    mut new_stream: NewS,
) -> ()
where
    IO: IOStream + Read + Send + Write + 'static,
    NewS: FnMut() -> io::Result<IO>,
    S: AsRef<str>,
{
    let (tx, rx) = channel();

    spawn_and_handle_signals(tx.clone());

    if wait {
        let start = time::Instant::now();

        for check in readiness_checks {
            let maybe_exit_code = match check {
                WaitTarget::Landlordd => wait_until_landlordd_ready(
                    &mut new_stream,
                    &rx,
                    time::Duration::from_millis(RETRY_DELAY_MILLIS),
                    &start,
                    wait_time,
                ),

                WaitTarget::Tcp(address) => wait_until_tcp_ready(
                    resolver,
                    address,
                    &rx,
                    time::Duration::from_millis(RETRY_DELAY_MILLIS),
                    &start,
                    wait_time,
                ),
            };

            if let Some(code) = maybe_exit_code {
                process::exit(code);
            }
        }
    }

    match new_stream() {
        Err(ref mut e) => {
            eprintln!("landlord: failed to connect: {:?}", e);

            process::exit(1);
        }

        Ok(mut stream) => {
            let result = install_fs_and_start(cp, props, class, args, &mut stream)
                .and_then(|pid| stream.try_clone().map(|stream_writer| (pid, stream_writer)))
                .and_then(|(pid, mut stream_writer)| {
                    spawn_and_handle_stdin(tx.clone());
                    spawn_and_handle_stream_read(stream, tx.clone());

                    handle_events(pid, &mut stream_writer, &rx, new_stream)
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
