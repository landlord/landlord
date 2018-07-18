#[derive(PartialEq, Debug)]
pub enum ExecutionMode {
    Class { class: String, args: Vec<String> },
    Exit { code: i32 },
    Help { code: i32 },
    JarFile { file: String, args: Vec<String> },
}

#[derive(PartialEq, Debug)]
pub enum Host {
    Tcp(String),
    Unix(String),
}

#[derive(PartialEq, Debug)]
pub struct JavaArgs {
    pub cp: Vec<String>,
    pub errors: Vec<String>,
    pub mode: ExecutionMode,
    pub props: Vec<(String, String)>,
    pub host: Host,
    pub version: bool,
    pub wait: bool,
}

fn default() -> JavaArgs {
    JavaArgs {
        cp: vec![".".to_string()],
        errors: vec![],
        mode: ExecutionMode::Help { code: 1 },
        props: vec![],
        host: Host::Unix("/var/run/landlord/landlordd.sock".to_string()),
        version: false,
        wait: false,
    }
}

/// Parses a JAVA_OPTS value, which is a space-delimited list of arguments
/// that can be provided as an environment variable. This often provides
/// some flexibility in operations. Whitespace can be escaped with a \ if
/// it should be part of the argument, i.e. "hello\ world" is a single
/// argument.
///
/// This implies that empty string arguments cannot be passed via JAVA_OPTS.
pub fn parse_java_opts<S: AsRef<str>>(opts: S) -> Vec<String> {
    let mut arg = String::new();
    let mut args = Vec::new();
    let mut escaped = false;

    for ch in opts.as_ref().chars() {
        if escaped {
            arg.push(ch);
            escaped = false;
        } else if ch == '\\' {
            escaped = true;
        } else if ch == ' ' {
            if !arg.is_empty() {
                args.push(arg);
                arg = String::new();
            }
        } else {
            arg.push(ch);
        }
    }

    if !arg.is_empty() {
        args.push(arg);
    }

    args
}

pub fn parse_java_args<S: AsRef<str>>(args: &[S]) -> JavaArgs {
    // We want to aim to be a drop-in replacement for java, so we have to roll our own arg parser
    // because DocOpt/Clap/et al don't have the required features to match the rather strange java
    // arguments.

    let noop_flags = ["-server", "-d64", "-d32"];

    let mut jargs = default();

    let mut iter = args.iter().map(|r| r.as_ref());

    loop {
        let next = iter.next();

        match next {
            Some(entry) if !entry.starts_with('-') => {
                let mut items = vec![];

                while let Some(next) = iter.next() {
                    items.push(next.to_string());
                }

                jargs.mode = ExecutionMode::Class {
                    class: entry.to_string(),
                    args: items,
                }
            }

            Some(flag) if flag == "-jar" => {
                if let Some(file) = iter.next() {
                    let mut items = vec![];

                    while let Some(next) = iter.next() {
                        items.push(next.to_string());
                    }

                    jargs.mode = ExecutionMode::JarFile {
                        file: file.to_string(),
                        args: items,
                    };
                } else {
                    jargs
                        .errors
                        .push(format!("{} requires jar file specification", flag))
                }
            }

            Some(flag) if flag == "-?" || flag == "-help" => {
                jargs.mode = ExecutionMode::Help { code: 0 };
            }

            Some(flag) if flag == "-version" => {
                jargs.version = true;
                jargs.mode = ExecutionMode::Exit { code: 0 };
            }

            Some(flag) if flag == "-showversion" => {
                jargs.version = true;
            }

            Some(flag) if flag == "-cp" || flag == "-classpath" => {
                if let Some(cp) = iter.next() {
                    jargs.cp = cp.split(':').map(|s| s.to_string()).collect();
                } else {
                    jargs
                        .errors
                        .push(format!("{} requires class path specification", flag))
                }
            }

            Some(flag) if flag == "-H" || flag == "-host" => {
                if let Some(host) = iter.next() {
                    if host.starts_with("tcp://") {
                        jargs.host = Host::Tcp(host[6..].to_string());
                    } else if host.starts_with("unix://") {
                        jargs.host = Host::Unix(host[7..].to_string());
                    } else {
                        jargs.errors.push(format!(
                            "{} must begin with \"tcp://\" or \"unix://\"",
                            flag
                        ))
                    }
                } else {
                    jargs
                        .errors
                        .push(format!("{} requires host specification", flag))
                }
            }

            Some(flag) if flag.starts_with("-D") => {
                if let Some(s) = flag.get(2..) {
                    let parts: Vec<&str> = s.splitn(2, '=').collect();

                    if let [key, value] = parts[..] {
                        jargs.props.push((key.to_string(), value.to_string()))
                    }
                }
            }

            Some(flag) if flag == "-wait" => {
                jargs.wait = true;
            }

            Some(flag) if noop_flags.contains(&flag) => {}

            Some(flag) => jargs.errors.push(format!("Unrecognized option: {}", flag)),

            None => {
                return jargs;
            }
        }
    }
}

#[test]
fn test_parse_java_opts() {
    assert_eq!(parse_java_opts(""), Vec::<String>::new());
    assert_eq!(parse_java_opts("one"), vec!["one"]);
    assert_eq!(
        parse_java_opts("one two three"),
        vec!["one", "two", "three"]
    );
    assert_eq!(
        parse_java_opts("hello\\ world two three"),
        vec!["hello world", "two", "three"]
    );
    assert_eq!(
        parse_java_opts("hello\\\\ trailing back slash"),
        vec!["hello\\", "trailing", "back", "slash"]
    );
    assert_eq!(parse_java_opts("  hello   world  "), vec!["hello", "world"]);
}

#[test]
fn test_parse_java_args_help() {
    assert_eq!(
        parse_java_args(&["-?"]),
        JavaArgs {
            mode: ExecutionMode::Help { code: 0 },
            ..default()
        }
    );

    assert_eq!(
        parse_java_args(&["-help"]),
        JavaArgs {
            mode: ExecutionMode::Help { code: 0 },
            ..default()
        }
    );
}

#[test]
fn test_parse_java_version() {
    assert_eq!(
        parse_java_args(&["-version"]),
        JavaArgs {
            mode: ExecutionMode::Exit { code: 0 },
            version: true,
            ..default()
        }
    );
}

#[test]
fn test_parse_java_showversion() {
    assert_eq!(
        parse_java_args(&["-showversion", "-jar", "test.jar"]),
        JavaArgs {
            mode: ExecutionMode::JarFile {
                file: "test.jar".to_string(),
                args: vec![],
            },
            version: true,
            ..default()
        }
    );
}

#[test]
fn test_parse_java_jar() {
    assert_eq!(
        parse_java_args(&["-jar", "test.jar", "arg1", "arg2"]),
        JavaArgs {
            mode: ExecutionMode::JarFile {
                file: "test.jar".to_string(),
                args: vec!["arg1".to_string(), "arg2".to_string()],
            },
            ..default()
        }
    );
}

#[test]
fn test_parse_host() {
    assert_eq!(
        parse_java_args(&vec![
            "-H".to_string(),
            "tcp://1.2.3.4:5678".to_string(),
            "HelloWorld".to_string(),
        ]).host,
        Host::Tcp("1.2.3.4:5678".to_string())
    );

    assert_eq!(
        parse_java_args(&vec![
            "-host".to_string(),
            "tcp://1.2.3.4:5678".to_string(),
            "HelloWorld".to_string(),
        ]).host,
        Host::Tcp("1.2.3.4:5678".to_string())
    );

    assert_eq!(
        parse_java_args(&vec![
            "-host".to_string(),
            "tcp://".to_string(),
            "HelloWorld".to_string(),
        ]).host,
        Host::Tcp("".to_string())
    );

    assert_eq!(
        parse_java_args(&vec![
            "-host".to_string(),
            "unix:///my-file".to_string(),
            "HelloWorld".to_string(),
        ]).host,
        Host::Unix("/my-file".to_string())
    );

    assert_eq!(
        parse_java_args(&vec![
            "-host".to_string(),
            "unix://".to_string(),
            "HelloWorld".to_string(),
        ]).host,
        Host::Unix("".to_string())
    );
}

#[test]
fn test_all() {
    assert_eq!(
        parse_java_args(&[
            "-Dkey1=value1",
            "-Dkey2=value2",
            "-d32",
            "-d64",
            "-server",
            "-wait",
            "-host",
            "unix:///dev/null",
            "-cp",
            "/lib:/usr/lib",
            "com.hello.Example",
            "myarg one",
            "myargtwo",
        ]),
        JavaArgs {
            cp: vec!["/lib".to_string(), "/usr/lib".to_string()],
            errors: vec![],
            mode: ExecutionMode::Class {
                class: "com.hello.Example".to_string(),
                args: vec!["myarg one".to_string(), "myargtwo".to_string()],
            },
            props: vec![
                ("key1".to_string(), "value1".to_string()),
                ("key2".to_string(), "value2".to_string()),
            ],
            host: Host::Unix("/dev/null".to_string()),
            version: false,
            wait: true,
        }
    );
}

#[test]
fn test_invalid_flags() {
    assert_eq!(
        parse_java_args(&["-hello-world", "com.hello.Example"]),
        JavaArgs {
            errors: vec!["Unrecognized option: -hello-world".to_string()],
            mode: ExecutionMode::Class {
                class: "com.hello.Example".to_string(),
                args: vec![],
            },
            ..default()
        }
    );
}
