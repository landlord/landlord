package com.github.huntc.landlord;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;

public class Main {
  static {
    Native.register(NativeLibrary.getProcess());
  }

  private static native int close(Integer fd);
  
  private static native int getpid();

  private static native int getppid();

  private static final int MAX_FDS = 1024; // Typical Linux soft limit for the max number of file descriptors

  /*
   * Get the file descriptors for the passed in pid assuming a procfs (https://en.wikipedia.org/wiki/Procfs).
   */
  private static List<Integer> getFds(Integer pid) {
    List<Integer> fds = new LinkedList<>();
    try {
      DirectoryStream<Path> dirStream = Files.newDirectoryStream(Paths.get("/proc/" + pid + "/fd"));
      try {
        for (Path path: dirStream) {
          try {
            Integer fd = Integer.parseInt(path.getFileName().toString());
            if (fds.size() < MAX_FDS) fds.add(fd); // Don't blow memory - constrain to a max
          } catch (NumberFormatException e) {
          }
        }
      } finally {
        dirStream.close();
      }
    } catch (IOException e) {
    }
    return fds;
  }

  /*
  * Close those file descriptors that are shared with our parent process.
  */
  private static void closeInheritedFds() {
    List<Integer> parentFds = getFds(getppid());
    List<Integer> ourFds = getFds(getpid());
    parentFds.retainAll(ourFds);
    parentFds
      .stream()
      .filter(fd -> fd > 2)
      .forEach(fd -> close(fd)); // We don't want to close stdin/out/err
  }

  /*
   * If we're operating on Linux then we will inherit or parent process file
   * descriptors. We need to close them so that we don't run out. See
   * https://github.com/brettwooldridge/NuProcess/issues/13#issuecomment-282071125
   * for more background.
   */
  public static void main(String[] args) throws ClassNotFoundException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
    if (System.getProperty("os.name").toLowerCase().startsWith("linux"))
      closeInheritedFds();

    Class<?> cls = Class.forName(args[0]);
    Method meth = cls.getMethod("main", args.getClass());
    meth.invoke(null, (Object)Arrays.copyOfRange(args, 1, args.length));
  }
}

