*** CONCEPT ***

# Landlord
Landlord provides the ability to run multiple JVM based applications on the one JVM thereby conserving resident memory *with no additional burden on the developer*. This is otherwise known as "multi tenancy", hence the name, "Landlord". :-)

> There are similar projects out there, including [Nailgun](https://github.com/martylamb/nailgun#nailgun). My requirements are to address security from the beginning and focus on using the mininum number of threads by avoiding blocking IO. In particular, each thread's stack space will take up 1MiB of memory by default (256k, best case) and I'm wanting to be very conservative in terms of memory usage). I'm also looking for isolation and want to consider cgroups at the thread level. While projects like Nailgun could perhaps be updated to accomodate my requirements, I feel that a clean start is appropriate. Retrofitting non-blocking/asynchronous behaviour in particular is difficult. Another goal is that I'd like there to be a very high degree of compatibility between running a JVM program normally vs Landlord. Hence the CLI tool it uses presents nearly all of the same arguments as the `java` command. A high degree of compatibility equates to wider adoption.

> For the record, I had also considered just using OSGi. However, OSGi appears to have failed to capture the hearts of developers (plus, just even trying to get started with Equinox, Felix and others feels quite broken - links not working, bad documentation etc.). My belief is that there shouldn't be any additional burden on the developer in order to leverage the benefits of multi-tenancy. Also, sharing modules is a benefit of OSGi that may be superceded by Java 9's module approach. I think that the benefit of using OSGi in order to reduce a JVM's footprint has been lost on people. Perhaps projects like this can fulfil this potential.

> [Ali Baba also have their own JVM implementation](https://www.youtube.com/watch?v=X4tmr3nhZRg) that could attain Landlord's objectives at a lower and more isolated level. So far as I understand, this JVM is not presently open source or publically available.

## Why
JVM programs take up too much resident memory. Back in the day of Java 1.1, a minimal JVM application outputting "Hello world" would take about about 4MiB of resident memory. Nowadays, the same program in Java 8 takes around 35MiB of resident memory i.e. almost 9 times as much! While Java 9's modules and AOT will help reduce the JVM's footprint, there's a lot of commonality between the JRE's of an individual JVM that can be shared.

Also, compare a typical JVM "microservice" to one written using a native target, such as LLVM; the JVM one will occupy around 350MiB which is more than 10 times the amount of memory when compared to a native one written in, say, Go. The JVM's consumption of memory may have been fine for the monolith, but when it comes to running many JVM based microservices (processes), their resident memory usage makes you want to program in something closer to the metal... or seek the "landlord" project!

Discounting the regular JVM overhead of runnning the first service, Running Landlord will reduce a typical "hello world" down to the requirements of its classpath.

## What
Landlord is a daemon service named `landlordd`. `landlordd` launches the JVM and runs some code that provides a secure Unix socket domain service where you can submit your JVM program to run. You may also send various [POSIX signals](https://en.wikipedia.org/wiki/Signal_(IPC)) that are trapped by your program in the conventional way for the JVM. You manage the daemon's lifecycle as per other services on your machines e.g. via initd. 

A client is also provided and named `landlord`. This client interfaces with `landlordd` and accepts a filesystem via stdin.

## How
From the command line, simply use the `landlord` command instead of the `java` tool and pass its filesystem via tar on `stdin` and you're good to go.

Under the hood, `landlord` will use a usergroup-secured Unix domain socket to send its arguments, including the streaming of the `tar` based file system from `stdin`. `landlordd` will consume the stream and create a new internal process invoked with a similar `java` command to the one it was invoked with. 

landlordd emulates the forking of a process within the same JVM by providing:
* a new classloader for each process that prohibits looking up landlord's and other process classes
* a security manager for each process
* stream redirection for each process
* properties for each process (including an updated and distinct user.dir)

Given that we cannot reliably detect when a thread group's threads have all terminated, there is a requirement on the developer to now call System.exit(status) when exiting - even with a 0 status code. If the developer doesn't call `System.exit` then their process will not exit. This is more flexible, but also consistent with C, C++ and others where a status code must be returned from the main function.

In addition, we warn about the adding of shutdown handlers given that they unavoidably apply to a process as a whole. Instead, a new `trap` function can be provided in place of shutdown handlers. Any signals passed to the process will be raised through this function. The `trap` function is declared as follows and must appear in the same class as the main function. Here is a full example in Java. The full example below permits both running within and outside of landlord:

```java
package example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

public class Hello {
    public static void main(String[] args) throws IOException {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("Shutdown trapped")));
        } catch (SecurityException ignored) {
            // An exception will be thrown when running via landlord started with --prevent-shutdown-hooks
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        System.out.println(br.readLine());
        System.exit(0);
    }

    @SuppressWarnings("unused")
    public static void trap(int signal) {
        System.out.println("Trapped: " + signal);
        if ((signal & 0xf) > 0)      // Terminate
          System.exit(128 + signal); // As per the convention of exit codes
    }
}
```

Upon compiling and supposing a folder containing our "hello world" class at `./hello-world/out/production/hello-world`:

> Note that the `landlord` client is not yet written. In order to launch landlordd:
> `daemon/target/universal/stage/bin/landlordd  --process-dir-path=/tmp/a --prevent-shutdown-hooks`
> In another terminal, invoke the `client.sh` script. The following describes how it things will look once the 
> client is written.

```
tar -c . | landlord -cp ./hello-world/out/production/hello-world Main
```

## Considerations/trade-offs

Some tradeoffs that have been identified:

* Multi-tenancy can introduce headaches because of inability to quota the heap and the inability to reason about how JIT and GC work for the aggregate JVM. This is a trade-off that OSGi has also accepted.

* The JIT indexes its work by classes within class loaders and so one "process's" JIT will not have a relation to another. This will result in more JIT activity, but an individual tenant might also get better performance in hot paths because JIT makes choices based on only that tenant's usage patterns, which might allow more aggressive inlining. This one is also no different to running multiple OS level processes.

* One process can bring down the entire JVM. This is a trade-off that OSGi also accepts.

Thanks to @retronym for his contribution to the above.

## landlord (TODO)
`landlord` will stream your commands to `landlordd` and then wait on a response. The response will yield the exit code from your program which will then cause `landlord` exit with the same response code.

Any POSIX signals sent to `landlord` while it is waiting for a reply will be forwarded onto `landlordd` and are then received by your program.

Note that in the case of long-lived programs (the most typical scenario for a microservice at least), then `landlord` will not return until your program terminates.

## landlordd
You can run as many `landlordd` daemons as you wish and as your system will allow. Quite often though, you should just need one.

(c)opyright 2017, Christopher Hunt
