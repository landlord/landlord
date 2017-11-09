package example;

import com.github.huntc.landlord.LandlordApp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

public class Hello extends LandlordApp {
    public static void main(String[] args) throws IOException {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("Shutdown trapped")));
        } catch (SecurityException ignored) {
            // An exception will be thrown when running via landlord started with --prevent-shutdown-hooks
        }

        Hello app = new Hello();
        ExecutorService executor = app.getSharedExecutor();
        System.out.println("shared executor object reference" + executor.toString());

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        System.out.println(br.readLine());
        System.exit(0);
    }

    @Override
    public void trap(int signal) {
        System.out.println("Trapped: " + signal);
        if ((signal & 0xf) > 0)             // Terminate
            System.exit(128 + signal); // As per the convention of exit codes
    }
}