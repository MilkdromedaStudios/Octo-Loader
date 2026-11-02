package studios.milkdromeda.octo.launcher;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;

import javax.swing.JOptionPane;

/**
 * What starts {@code octo-launcher.jar}: built for Java 8, so that on a Java
 * too old for Octo Launcher it can still say so plainly, where Java itself
 * would stop with an error nobody can read (or, double-clicked, show nothing).
 * On Java 17 or newer it hands straight over to {@code Launcher}.
 */
public final class Boot {
    static final int NEEDED = 17;

    private Boot() {
    }

    public static void main(String[] args) throws Throwable {
        int running = javaVersion(System.getProperty("java.specification.version", "0"));

        if (running < NEEDED) {
            String message = "Octo Launcher needs Java " + NEEDED + " or newer, and this is Java " + running + ".\n\n"
                    + "On Windows, use OctoLauncher-Setup.exe from the releases page instead: it brings its own Java.\n"
                    + "Otherwise install Java 21 from https://adoptium.net and open octo-launcher.jar again.\n\n"
                    + "Releases: https://github.com/MilkdromedaStudios/Octo-Loader/releases/latest";
            System.err.println(message);

            if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, message, "Octo Launcher", JOptionPane.WARNING_MESSAGE);
            }

            System.exit(1);
            return;
        }

        try {
            Class.forName("studios.milkdromeda.octo.launcher.Launcher").getMethod("main", String[].class)
                    .invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** "1.8" is 8, "17" is 17. */
    static int javaVersion(String specification) {
        String version = specification.startsWith("1.") ? specification.substring(2) : specification;
        int end = 0;

        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }

        return end == 0 ? 0 : Integer.parseInt(version.substring(0, end));
    }
}
