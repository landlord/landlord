package example;

/**
 * An example used to demonstrate addShutdownHook behavior when running in
 * landlordd.
 */
public class Shutdown {
    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("Shutdown trapped")));

        System.exit(0);
    }

    @SuppressWarnings("unused")
    public static void trap(int signal) {
        System.out.println("Trapped: " + signal);
        if ((signal & 0xf) > 0)             // Terminate
          System.exit(128 + signal); // As per the convention of exit codes
    }
}
