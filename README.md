*** EXPERIMENTAL ***

# Landlord - Containers for the JVM
Landlord is targeted at reducing the costs of supporting the JVM when running in production. Landlord provides the ability to run multiple JVM based applications on the one JVM thereby conserving resident memory *with little burden on the developer*. This sharing is otherwise known as "multi tenancy", hence the name, "Landlord". :-) The more that can be shared, the less memory is required on a single machine, the more services that can be run, the less machines required, the more money saved, the better the planet given the reduced energy requirements.

## Similar projects/initiatives
There are similar projects out there, including [Nailgun](https://github.com/martylamb/nailgun#nailgun). Our requirements are to address security from the beginning and focus on using the mininum number of threads by avoiding blocking IO. In particular, each thread's stack space will take 256k to 1MiB of memory and we're wanting to be very conservative in terms of memory usage. We're also looking for isolation and wish to consider cgroups at the thread level eventually. While projects like Nailgun could perhaps be updated to accomodate our requirements, we feel that a clean start is appropriate; retrofitting non-blocking/asynchronous behaviour in particular is difficult. Another goal is that we'd like there to be a very high degree of compatibility between running a JVM program normally vs Landlord. Hence the CLI tool it uses presents nearly all of the same arguments as the `java` command. A high degree of compatibility equates to wider adoption.

We had also considered just using OSGi. However, OSGi appears to have failed to capture the hearts of developer. Plus, even trying to get started with Equinox, Felix and others feels quite broken - links not working, bad documentation etc. Our belief is that there should be very little burden on the developer to leverage the benefits of multi-tenancy. Also, sharing modules is a benefit of OSGi that may be superceded by Java 9's module approach. We think that the benefit of using OSGi in order to reduce a JVM's footprint has been lost on people. Perhaps projects like this can fulfil that potential.

[Ali Baba also have their own JVM implementation](https://www.youtube.com/watch?v=X4tmr3nhZRg) that could attain Landlord's objectives at a lower and more isolated level. So far as we understand, this JVM is not presently open source or publically available.

[AOT](http://openjdk.java.net/jeps/295) via [Graal](https://www.graalvm.org/)'s native imaging could also diminish the need for landlord, as the JVM process reduces its memory footprint toward that of native languages such as [Go](https://golang.org/). So, perhaps landlord has a short lifespan in this regard; doubly important then that the impact of landlord on the programmer is minimal in order to faciliate potential future migrations.

[IBM's J9 JRE](https://www.ibm.com/support/knowledgecenter/en/SSYKE2_7.0.0/com.ibm.java.lnx.70.doc/user/java_jvm.html) (which [OpenJ9](https://www.eclipse.org/openj9/) is derived from) [provided a multi-tenant JVM in 2013 as a technology preview](https://www.ibm.com/developerworks/library/j-multitenant-java/index.html). This multitenant JVM looked as though it would fully achieve Landlord's objectives. However, at least with OpenJ9, the multitenancy feature seems to be missing. Please raise an issue on this README if more information can be provided.

## Why use Landlord
JVM programs take up too much resident memory. Back in the day of Java 1.1, a minimal JVM application outputting "Hello world" would take about about 4MiB of resident memory. Nowadays, the same program in Java 8 takes around 35MiB of resident memory i.e. almost 9 times as much! While Java 9's modules and AOT will help reduce the JVM's footprint, there remains a lot of commonality between the JRE's of an individual JVM that can be shared.

Also, compare a typical JVM "microservice" to one written using a native target, such as LLVM. The JVM one will occupy around 350MiB which is more than 10 times the amount of memory when compared to a native one written in, say, Go. The JVM's consumption of memory may have been fine for the monolith, but when it comes to deploying many JVM based microservices as processes, their resident memory usage makes you want to program in something closer to the metal... or seek the "landlord" project!

Discounting the regular JVM overhead of runnning the first service, running Landlord will reduce a typical "hello world" down to the requirements of its classpath. We've observed a simple Java Hello World app consuming less than 1MiB of resident memory compared to 35MiB when running as a standalone process. Bear in mind though that Landlord itself will realistically require 250MiB including room for running several processes (all depending on what the processes require of course!).

## What is Landlord
Landlord has a daemon service named `landlordd`. `landlordd` launches the JVM and runs some code that provides a secure Unix socket domain service where you can submit your JVM program to run. You may also send various [POSIX signals](https://en.wikipedia.org/wiki/Signal_(IPC)) that are trapped by your program in the conventional way for the JVM. You manage the daemon's lifecycle as per other services on your machines e.g. via initd.

A client is also provided and named `landlord`. This client interfaces with `landlordd` to run your program, and provides an interface that is a drop-in replacement for the `java` command. The client provides the illusion that your program is running within it, in the same way that Docker clients do as they run containers via a Docker daemon. Landlord's architecture is very similar to Docker in this way i.e. there are clients and there is a daemon. One important difference though is that when your client terminates, so does its "process" as managed by the daemon i.e. there is deliberately no "detach" mode in order to reduce the potential for orphaned processes. Landlord clients themselves can be detached though. The goal is for the Landlord client to behave as your process would without Landlord, relaying signals etc.

## Quick Start

To get started with Landlord, you'll need [sbt](https://www.scala-sbt.org/) and [cargo](https://doc.rust-lang.org/cargo/). Once installed, the following will launch `landlordd` and run a couple Java programs in it by using the `landlord` client.

1) Build `landlordd` and `landlord`

```bash
(cd landlordd && sbt daemon/stage) && (cd landlord && cargo build --release)
```

2) Start `landlordd`

Create a shared folder for hosting Unix Domain Socket files:

```bash
sudo mkdir /var/run/landlord
sudo chown $LOGNAME /var/run/landlord
```

Next, start the `landlordd` daemon process.

```bash
landlordd/daemon/target/universal/stage/bin/landlordd
```

3) Run `Hello.java`

In another terminal, let's try the first example program, `Hello.java`. This program prints a greeting to the screen and then forwards `stdin` to `stdout`.

First, let's use the regular `java` command, i.e. not using Landlord.

```bash
echo Testing... | java -cp landlordd/test/target/scala-2.12/classes "-Dgreeting=Welcome" example.Hello ArgOne ArgTwo
```

```
Argument #1: ArgOne
Argument #2: ArgTwo
Welcome!
Testing...
```

Now for Landlord. The following commands will submit the same program to `landlordd` for execution and provide the illusion of it running within your shell.

```bash
echo Testing... | landlord/target/release/landlord -cp landlordd/test/target/scala-2.12/classes "-Dgreeting=Welcome" example.Hello ArgOne ArgTwo
```

```
Argument #1: ArgOne
Argument #2: ArgTwo
Welcome!
Testing...
```

CONGRATULATIONS! You've just run your first Landlord-hosted program! Let's try another...

4) Run `Count.java`

`Count.java` prints a message to the screen once every second until it it sent a signal, at which point it exits with the value of that signal.

First, with the `java` command, i.e. not using Landlord. We'll start the program, wait a few seconds, and press `CTRL-C`.

```bash
java -cp landlordd/test/target/scala-2.12/classes example.Count || echo "Exited with $?"
```

```
Iteration #0
Iteration #1
Iteration #2
^CExited with 130
```

Now it is Landlord's turn again. Again, press `CTRL-C` after a few seconds.

```bash
landlord/target/release/landlord -cp landlordd/test/target/scala-2.12/classes example.Count || echo "Exited with $?"
```

```
Iteration #0
Iteration #1
Iteration #2
^CExited with 128
```

You've now run two different programs in Landlord, with the second one illustrating how Unix signals are catered for.

## How to use Landlord
From the command line, simply use the `landlord` command instead of the `java` tool and you're good to go.

Under the hood, `landlord` will use a usergroup-secured Unix domain socket to send its arguments, including the streaming of the `tar` based file system specified by the `-cp` argument. `landlordd` will consume the stream and create a new internal "process" with any parameters and flags forwarded from the original `landlord` command.

`landlordd` emulates the forking of a process within the same JVM by providing:
* a new classloader for each process that prohibits looking up landlord's and other process classes
* a security manager for each process
* stream redirection for each process
* properties for each process (including an updated and distinct user.dir)

Given that we cannot reliably detect when a thread group's threads have all terminated, there is a requirement on the developer to now call `System.exit(status)` when exiting - even with a 0 status code. If the developer doesn't call `System.exit` then their process will not exit. This is consistent with C, C++ and others where a status code must be returned from the main function, but a necessary departure from a regular JVM program.

> Detailed information: threads are quite often created with a default thread group in JVM programs e.g. when using `ThreadPoolExecutor`. Therefore, it is not possible to discern the "process threads" from the ones Landlord uses e.g. with stdin/out/err. The only reliable method of determining when a process is finished is when the developer says so i.e. `System.exit`.

When an application is terminating (i.e. after the program has called `System.exit(status)`, its registered shutdown hooks (registered via `Runtime.getRuntime().addShutdownHook(..)`) will be called. Additionally, a new `trap` function can be used to capture signals at a more detailed level than the JVM provides. Any signals passed to the process (via the client) will be raised through this function. The `trap` function is declared as follows and must appear in the same class as the main function. Here is a full example in Java. The full example below permits both running within and outside of landlord:

```java
package example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

public class Hello {
    public static void main(String[] args) throws IOException {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("Shutdown trapped")));

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String line = br.readLine();
        if (line != null) System.out.println(line);

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

```bash
landlord -cp ./hello-world/out/production/hello-world Main
```

## Considerations/trade-offs
Here are some tradeoffs that have been identified:

* Multi-tenancy can introduce headaches because of inability to quota the heap and reason about how JIT and GC work for the aggregate JVM. Note that this is a trade-off that OSGi has also accepted.

* The JIT indexes its work by classes within class loaders and so one "process's" JIT will not have a relation to another. This will result in more JIT activity, but an individual tenant might also get better performance in hot paths because JIT makes choices based on only that tenant's usage patterns, which might allow more aggressive inlining. This one is also no different to running multiple OS level processes.

* There’s plenty of scope for sharing language, toolkit and framework classes such as Scala, Akka and Play etc. However, what we’ve observed so far is that the heap and stack size are the two largest cost saving areas. Classes themselves are quite small and so we're not sure it is worth the additional complexity. It can be done though and perhaps there's a way of leveraging [JDK9's module system](http://openjdk.java.net/projects/jigsaw/) to facilitate this.

* One tenant process can bring down the entire JVM, which is also a trait of OSGi. Bulkheads are important and there are degrees of isolation available in general e.g.: process, container, unikernel, VM with process level isolation being quite common. However, the JVM based process presently consumes a considerable order of magnitude more memory than its native counterparts (Go, Rust etc.). Process level isolation is not a good option where memory resources are constrained. Hence thread group/classloader is the next best thing if the risk of all tenants going down can be mitigated.

    * As a sub-point to the above, Out Of Memory (OOM) would be the failure mode for bringing down the entire JVM. Given that destroying threads is discouraged, all one can really do is interrupt them. Then it might be too late... or just ineffective. It would be great if the JVM itself offered the facility of managing custom GC regions from the JVM language. We could then assign a GC to a thread group, destroying the GC when the thread group is closed. Anyhow, custom GC assignment isn’t available and we are looking to avoid hacking on the JVM (although we might!).

    * In terms of mitigating failure, the `landlord` can always re-submit its process should it lose connectivity with `landlordd`. This assumes that there is some other supervisor to `landlordd` that will restart it when it shuts down with a non-zero exit code.

Thanks to @retronym, @dragos and @dotta for their contributions to the above.

## landlord
`landlord` (the client) streams stdin to `landlordd` until it receives a response. The response yields the exit code from your program which will then cause `landlord` to exit with the same response code.

Any POSIX signals sent to `landlord` while it is waiting for a reply will be forwarded onto `landlordd` and are then received by your program.

Note that in the case of long-lived programs (the most typical scenario for a microservice at least), `landlord` will not return until your program terminates.

## landlordd
You can run as many `landlordd` daemons as your system will allow. Quite often though, you should just need one, although you may have multiple to partition bulk heading between "critical" and "non-critical" services (for example).

## Docker packaging

Both the client and daemon are published to Docker under the `landlord` organization. If you need to build them locally then this section is for you.

Prior to running any of the following, ensure that you have the shared socket directory setup:

```
docker volume create \
  --driver local \
  --opt type=tmpfs \
  --opt device=tmpfs \
  --opt o=uid=2 \
  landlord
```

### landlordd

```
(cd landlordd && sbt daemon/docker:publishLocal)
```

...and then run it assuming the image being published as `daemon:0.1.0-SNAPSHOT` (substitute accordingly):

```
docker run \
  --rm \
  -v landlord:/var/run/landlord \
  daemon:0.1.0-SNAPSHOT
```

To publish to the Docker registry, if you have permission, and the image being published as `daemon:0.1.0-SNAPSHOT` (substitute accordingly):

```
docker tag daemon:0.1.0-SNAPSHOT landlord/daemon
docker push landlord/daemon
```

### landlord

First, cross build the client for the Linux target. If you're on Linux then this is relatively straightforward:

```
(cd landlord && rustup target add x86_64-unknown-linux-musl && cargo build --target=x86_64-unknown-linux-musl --release)
```

If you have OS X then you're going to need to invoke Docker to perform the build (cross compiling on OS X is problematic). 
Here's the command for OS X:

```
(cd landlord && \
 docker run --rm \
   -v $PWD:/volume \
   -v ~/.cargo:/root/.cargo \
   -t clux/muslrust \
   cargo build --release)
```

We can now build the docker image:

```
(cd landlord && docker build --no-cache -t landlord/landlord .)
```

Optionally, you can quickly test whether the build worked with the following command. The command will print out landlord's options. You can also see that landlord resides in `/usr/local/bin`.

```
docker run --rm  landlord/landlord /usr/local/bin/landlord
```

To publish to the Docker registry, if you have permission:

```
docker push landlord/landlord
```

### Base image

Now that we have a small base image, `landlord/landlord` (this takes up about 10MB of disk and about 4MiB of RAM when run), we can build and run our JVM based applications.

To build:

```
(cd landlordd && sbt compile && docker build --no-cache -t landlord/hello .)
```

...then (having also started landlord/daemon) to run:

```
docker run \
  --rm \
  -v landlord:/var/run/landlord \
  landlord/hello
```

The Dockerfile for the above is quite minimal and is reproduced below for convenience:

```
FROM landlord/landlord
USER daemon
COPY test/target/scala-2.12/classes /classes
ENTRYPOINT ["/usr/local/bin/landlord", "-cp", "/classes", "-Dgreeting=Welcome", "example.Hello", "ArgOne", "ArgTwo"]
```

Lastly, to publish to the Docker registry, if you have permission:

```
docker push landlord/hello
```


Profiling tooling is gratefully donated by YourKit LLC ![logo](https://www.yourkit.com/images/yklogo.png)

_YourKit supports open source projects with its full-featured Java Profiler.
YourKit, LLC is the creator of <a href="https://www.yourkit.com/java/profiler/">YourKit Java Profiler</a>
and <a href="https://www.yourkit.com/.net/profiler/">YourKit .NET Profiler</a>,
innovative and intelligent tools for profiling Java and .NET applications._

(c)opyright 2017, Christopher Hunt
