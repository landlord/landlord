use byteorder::{BigEndian, ReadBytesExt, WriteBytesExt};
use std::io;
use std::io::prelude::*;

pub enum Input {
    Exit(i32),
    Fail(io::Error),
    Signal(i32),
    StdIn(Vec<u8>),
    StdInClosed,
    StdOut(Vec<u8>),
    StdErr(Vec<u8>),
}

/// Allocates a buffer of `num` bytes and reads that exact number
/// of bytes from `stream`
pub fn read_bytes(read: &mut Read, num: usize) -> io::Result<Vec<u8>> {
    let mut buf = vec![0; num];

    read.read_exact(&mut buf).map(|_| buf)
}

/// core event loop that reads events from a provided reader and
/// handles the events accordingly
pub fn input_handler<R, W, SW, StdOut, StdErr>(
    pid: i32,
    mut reader: R,
    mut writer: W,
    mut single_session_writer: SW,
    mut stdout: StdOut,
    mut stderr: StdErr,
) -> io::Result<i32>
where
    R: FnMut() -> io::Result<Input>,
    W: FnMut(Vec<u8>) -> io::Result<()>,
    StdOut: FnMut(Vec<u8>) -> io::Result<()>,
    SW: FnMut(Vec<u8>) -> io::Result<()>,
    StdErr: FnMut(Vec<u8>) -> io::Result<()>,
{
    loop {
        match reader() {
            Ok(Input::Exit(s)) => {
                return Ok(s);
            }

            Ok(Input::Fail(e)) | Err(e) => {
                return Err(e);
            }

            Ok(Input::StdIn(b)) => {
                if !b.is_empty() {
                    if let Err(e) = writer(b) {
                        return Err(e);
                    }
                }
            }

            Ok(Input::StdInClosed) => {
                if let Err(e) = writer(vec![]) {
                    return Err(e);
                }
            }

            Ok(Input::Signal(s)) => {
                let result = encode_i32(pid)
                    .and_then(|pid_bytes| encode_i32(s).map(|sig_bytes| (pid_bytes, sig_bytes)))
                    .and_then(|(ref mut pid_bytes, ref mut sig_bytes)| {
                        let mut data = vec![];

                        data.push(b'k');
                        data.append(pid_bytes);
                        data.append(sig_bytes);

                        single_session_writer(data)
                    });

                if let Err(e) = result {
                    return Err(e);
                }
            }

            Ok(Input::StdOut(b)) => {
                if let Err(e) = stdout(b) {
                    return Err(e);
                }
            }

            Ok(Input::StdErr(b)) => {
                if let Err(e) = stderr(b) {
                    return Err(e);
                }
            }
        }
    }
}

/// manages reading the socket (landlord protocol)
pub fn read_handler<R, W>(mut reader: R, mut writer: W) -> io::Result<()>
where
    R: FnMut(usize) -> io::Result<Vec<u8>>,
    W: FnMut(Input) -> io::Result<()>,
{
    loop {
        match reader(1).map(|bs| bs[0]) {
            Ok(b'e') => {
                let result = read_payload(&mut reader)
                    .map(Input::StdErr)
                    .and_then(|msg| writer(msg));

                if result.is_err() {
                    return result;
                }
            }
            Ok(b'o') => {
                let result = read_payload(&mut reader)
                    .map(Input::StdOut)
                    .and_then(|msg| writer(msg));

                if result.is_err() {
                    return result;
                }
            }
            Ok(b'x') => {
                return reader(4)
                    .and_then(|bs| decode_i32(&bs))
                    .and_then(|code| writer(Input::Exit(code)));
            }
            Ok(other) => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("Unknown code: {}", other),
                ))
            }
            Err(err) => {
                return Err(err);
            }
        }
    }
}

/// reads the process id from the provided `stream`
pub fn read_pid_handler(stream: &mut Read) -> Option<i32> {
    read_bytes(stream, 4)
        .ok()
        .and_then(|bs| io::Cursor::new(bs).read_i32::<BigEndian>().ok())
}

/// Creates the first line of data that is sent to landlordd when loading an app.
pub fn app_cmdline<S: AsRef<str>>(
    class_path_with_names: &[(String, String, String)],
    props: &[(S, S)],
    class: &S,
    args: &[S],
) -> String {
    let props = props
        .iter()
        .map(|&(ref n, ref v)| format!("-D{}={}", n.as_ref(), v.as_ref()))
        .collect::<Vec<String>>()
        .join("\u{0000}");

    format!(
        "l{}-cp\u{0000}{}\u{0000}{}{}\n",
        if props == "" {
            props
        } else {
            format!("{}\u{0000}", props)
        },
        class_path_with_names
            .iter()
            .map(|&(_, _, ref cp_name)| cp_name.as_ref())
            .collect::<Vec<&str>>()
            .join(":"),
        class.as_ref(),
        if args.is_empty() {
            "".to_string()
        } else {
            format!(
                "\u{0000}{}",
                args.iter()
                    .map(|a| a.as_ref())
                    .collect::<Vec<&str>>()
                    .join("\u{0000}")
            )
        }
    )
}

/// Given class path entries, returns a new vector containing entries
/// as a tuple, each element: (path, name to store in tar file, name for classpath argument)
pub fn class_path_with_names<S: AsRef<str>>(class_path: &[S]) -> Vec<(String, String, String)> {
    class_path
        .iter()
        .enumerate()
        .map(|(ref i, e)| {
            let e = e.as_ref();

            if e.ends_with('*') {
                (
                    e[..e.len() - 1].trim_right_matches('/').to_string(),
                    i.to_string(),
                    format!("{}/*", i.to_string()),
                )
            } else {
                (e.to_string(), i.to_string(), i.to_string())
            }
        }).collect()
}

/// given a reader, reads a landlord payload, i.e. a 4-byte encoded (big endian) size followed
/// by that number of bytes.
fn read_payload<R>(reader: &mut R) -> io::Result<Vec<u8>>
where
    R: FnMut(usize) -> io::Result<Vec<u8>>,
{
    reader(4)
        .and_then(|bs| decode_i32(&bs))
        .and_then(|size| reader(size as usize))
}

fn decode_i32(vec: &[u8]) -> io::Result<i32> {
    io::Cursor::new(vec).read_i32::<BigEndian>()
}

fn encode_i32(value: i32) -> io::Result<Vec<u8>> {
    let mut buf = vec![];

    buf.write_i32::<BigEndian>(value).map(|_| buf)
}

#[test]
fn test_app_cmdline_no_args() {
    assert_eq!(
        app_cmdline(
            &[
                ("/test1/one".to_string(), "0".to_string(), "0".to_string()),
                ("/test1/two".to_string(), "1".to_string(), "1".to_string()),
                (
                    "/test1/three".to_string(),
                    "2".to_string(),
                    "2/*".to_string()
                ),
            ],
            &[],
            &"com.example.HelloWorld1",
            &[]
        ),
        "l-cp\u{0000}0:1:2/*\u{0000}com.example.HelloWorld1\n".to_string()
    )
}

#[test]
fn test_app_cmdline_with_args() {
    assert_eq!(
        app_cmdline(
            &[
                ("/test2/one".to_string(), "0".to_string(), "0".to_string()),
                ("/test2/two".to_string(), "1".to_string(), "1".to_string()),
            ],
            &[],
            &"com.example.HelloWorld2",
            &["argone", "arg two"]
        ).as_str(),
        "l-cp\u{0000}0:1\u{0000}com.example.HelloWorld2\u{0000}argone\u{0000}arg two\n"
    )
}

#[test]
fn test_app_cmdline_with_args_props() {
    assert_eq!(
        app_cmdline(
            &[
                ("/test2/one".to_string(), "0".to_string(), "0".to_string()),
                ("/test2/two".to_string(), "1".to_string(), "1".to_string()),
            ],
            &[("one", "#1!"), ("two", "#2!")],
            &"com.example.HelloWorld2",
            &["argone", "arg two"]
        ).as_str(),

        "l-Done=#1!\u{0000}-Dtwo=#2!\u{0000}-cp\u{0000}0:1\u{0000}com.example.HelloWorld2\u{0000}argone\u{0000}arg two\n"
    )
}

#[test]
fn test_class_path_with_names() {
    assert_eq!(
        class_path_with_names(&["/test/one", "/test/two", "/test/three/*"]),
        vec![
            ("/test/one".to_string(), "0".to_string(), "0".to_string()),
            ("/test/two".to_string(), "1".to_string(), "1".to_string()),
            (
                "/test/three".to_string(),
                "2".to_string(),
                "2/*".to_string(),
            ),
        ]
    );
}

#[test]
fn test_decode_i32_invalid() {
    assert!(decode_i32(&vec![]).is_err());
    assert!(decode_i32(&vec![0]).is_err());
    assert!(decode_i32(&vec![0, 0]).is_err());
    assert!(decode_i32(&vec![0, 0, 0]).is_err());
}

#[test]
fn test_decode_i32_valid() {
    assert_eq!(decode_i32(&vec![0, 0, 0, 0]).ok(), Some(0));
    assert_eq!(decode_i32(&vec![0, 0, 0, 1]).ok(), Some(1));
    assert_eq!(decode_i32(&vec![1, 0, 0, 0]).ok(), Some(16777216));
    assert_eq!(decode_i32(&vec![1, 0, 0, 1]).ok(), Some(16777217));
}

#[test]
fn test_encode_i32_valid() {
    assert_eq!(encode_i32(0).ok(), Some(vec![0, 0, 0, 0]));
    assert_eq!(encode_i32(1).ok(), Some(vec![0, 0, 0, 1]));
    assert_eq!(encode_i32(16777216).ok(), Some(vec![1, 0, 0, 0]));
    assert_eq!(encode_i32(16777217).ok(), Some(vec![1, 0, 0, 1]));
}
