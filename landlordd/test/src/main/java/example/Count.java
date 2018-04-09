package example;

import java.io.IOException;

public class Count {
    private static volatile int exitCode = -1;

    public static void main(String[] args) throws IOException {
        for (String arg: args) {
            System.out.println(arg);
        }

        for (int i = 0; exitCode < 0; i++) {
            System.out.println("Iteration #" + i);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                exitCode = 1;
            }
        }

        System.exit(exitCode);
    }

    @SuppressWarnings("unused")
    public static void trap(int signal) {
        System.out.println("Trapped: " + signal);
        if ((signal & 0xf) > 0)             // Terminate
            exitCode = 128 + signal; // As per the convention of exit codes
    }
}
