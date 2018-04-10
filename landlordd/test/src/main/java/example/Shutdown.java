package example;

import java.io.IOException;

/**
 * An example used to demonstrate addShutdownHook behavior when running in
 * landlordd.
 */
public class Shutdown {
    public static void main(String[] args) throws IOException {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("Shutdown trapped")));
        } catch (SecurityException ignored) {
            // An exception will be thrown when running via landlord started with --prevent-shutdown-hooks
        }

        System.exit(0);
    }

    @SuppressWarnings("unused")
    public static void trap(int signal) {
        System.out.println("Trapped: " + signal);
        if ((signal & 0xf) > 0)             // Terminate
          System.exit(128 + signal); // As per the convention of exit codes
    }
}
