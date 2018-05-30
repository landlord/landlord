package example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

/**
 * A simple example showing the usage of arguments, properties, and stdin.
 * When run with `landlordd` and `landlord`, it should behave as a drop-in
 * replacement to the `java` command.
 */
public class Hello {
    public static void main(String[] args) throws IOException {
        // Landlord will call interrupt on the threads of programs it manages so
        // that they can cooperatively terminate. We help test this by launching
        // a thread that doesn't terminate until it has been interrupted.

        new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();

        // Landlord will invoke your shutdown hooks when it unloads your program.

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> System.out.println("Good Bye!"))
        );

        int i = 0;

        for (String arg: args) {
            i++;
            System.out.println(String.format("Argument #%s: %s", i, arg));
        }

        System.out.println(System.getProperty("greeting", "Welcome. Please type a message."));

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String line = br.readLine();
        if (line != null) System.out.println(line);

        System.exit(0);
    }

    @SuppressWarnings("unused")
    public static void trap(int signal) {
        System.out.println("Trapped: " + signal);
        if ((signal & 0xf) > 0)             // Terminate
          System.exit(128 + signal); // As per the convention of exit codes
    }
}
