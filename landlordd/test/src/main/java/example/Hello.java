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
        if ((signal & 0xf) > 0)             // Terminate
          System.exit(128 + signal); // As per the convention of exit codes
    }
}