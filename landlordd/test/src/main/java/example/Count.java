package example;

import java.io.IOException;

/**
 * A test program that counts until it receives a signal, and
 * then exits with the value of the signal it received (plus 128).
 * Used to test that signals are being handled correctly when
 * using `landlordd` and `landlord`.
 */
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
        exitCode = 128 + signal;
    }
}
