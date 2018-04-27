package example;

import dep.Greeting;

/**
 * A test program that prints the value of dep.Greeting.value
 * which is loaded dynamically via landlord's class profile support
 */
public class Profile {
    public static void main(String[] args) {
        try {
            System.out.println(Greeting.value);

            System.exit(0);
        } catch(NoClassDefFoundError e) {
            System.err.println(String.format("Unable to load class \"%s\".", e.getMessage()));
            System.exit(1);
        }
    }
}
