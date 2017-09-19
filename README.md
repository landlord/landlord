*** CONCEPT ***

# Landlord
Landlord provides the ability to run multiple JVM based applications on the one JVM. This is otherwise known as "multi tenancy", hence the name, "Landlord". :-)

> There are similar projects out there, including [Nailgun](https://github.com/martylamb/nailgun#nailgun). My requirements are to address security from the beginning and focus on using the mininum number of threads by avoiding blocking IO. In particularly, each thread's stack space will take up 1MiB of memory and I'm wanting to be very conservative in terms of memory usage). I'm also looking for isolation and want to consider cgroups. While projects like Nailgun could perhaps be updated to accomodate my requirements, I feel that a clean start is appropriate. Retrofitting non-blocking/asynchronous behaviour in particular is difficult. Another goal is that I'd like there to be a very high degree of compatibility between running a JVM program normally vs Landlord. Hence the CLI tool it uses presents nearly all of the same arguments as the `java` command. A high degree of compatibility equates to wider adoption.

## Why
JVM programs take up too much resident memory. Back in the day of Java 1.1, a minimal JVM application outputting "Hello world" would take about about 4MiB of resident memory. Nowadays, the same program in Java 8 takes around 45MiB of resident memory i.e. over 10 times as much! While Java 9's modules may help reduce the JVM's footprint, there's a lot of commonality between the JRE's of an individual JVM that can be shared.

Also, compare a typical JVM "microservice" to one written using a native target, such as LLVM; the JVM one will occupy around 350MiB which is more than 10 times the amount of memory when compared to a native one. The JVM's consumption of memory may have been fine for the monolith, but when it comes to running many JVM based microservices (processes), their resident memory usage makes you want to program in something closer to the metal... or seek the "landlord" project!

Discounting the regular JVM overhead of runnning the first service, Running Landlord will reduce a typical "hello world" down to the requirements of its classpath.

## What
Landlord is a daemon service named `landlordd`. `landlordd` launches the JVM and runs some code that provides a secure RESTful HTTP service where you can submit your JVM program to run. You may also send various [POSIX signals](https://en.wikipedia.org/wiki/Signal_(IPC)) that are trapped by your program in the conventional way for the JVM. You manage the daemon's lifecycle as per other services on your machines e.g. via initd. 

A client is also provided and named `landlord`. This client interfaces with `landlordd` and accepts a filesystem via stdin.

## How
From the command line, simply use the `landlord` command instead of the `java` tool and pass its filesystem via tar on `stdin` and you're good to go.

Under the hood, `landlord` will use a usergroup-secured Unix domain socket send of its arguments, including the streaming of the `tar` based file system from `stdin`. `landlordd` will consume the stream and create a new process invoked with the same `java` command that it was invoked with. Most operating systems perform a copy-on-read of memory segments when forking processes and thus share most of the JVM's memory (thanks to [Jason Longshore](https://github.com/longshorej) for highlighting this to me). Complete process isolation can then be attained.

### An example

The obligatory "hello world":

```java
public class Main {

    public static void main(String[] args) {
        System.out.println("Hello World!");
    }
}
```

Upon compiling and supposing a folder containing our "hello world" class at `./hello-world/out/production/hello-world`:

```
tar -c . | landlord -cp ./hello-world/out/production/hello-world Main
```

## landlord
`landlord` will stream your commands to `landlordd` and then wait on a response. The response will yield the exit code from your program which will then cause `landlord` exit with the same response code.

Any POSIX signals sent to `landlord` while it is waiting for a reply will be forwarded onto `landlordd` and received by your program.

Note that in the case of long-lived programs (the most typical scenario for a microservice at least), then `landlord` will not return until your program terminates.

## landlordd
You can run as many `landlordd` daemons as you wish and as your system will allow.

(c)opyright 2017, Christopher Hunt
