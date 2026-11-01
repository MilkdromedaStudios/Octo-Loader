package studios.milkdromeda.octo.launcher.ui;

import java.awt.Color;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JFrame;

import studios.milkdromeda.octo.launcher.LauncherSettings;

/** What every page can ask of the window. */
public interface App {
    LauncherSettings settings();

    JFrame frame();

    /** Shows a page by name: Home, Mods, Worlds, Versions, Settings. */
    void go(String page);

    /** A short message in the corner that goes away by itself. */
    void toast(String message, Color colour);

    /**
     * Work off the window's thread; {@code done} runs on it after. A failure
     * is shown as a toast.
     */
    <T> void run(Callable<T> work, Consumer<T> done);

    /** As {@link #run(Callable, Consumer)}, telling the page when the work fails, too. */
    <T> void run(Callable<T> work, Consumer<T> done, Consumer<Throwable> failed);

    /** Something changed on disk: every page reads again. */
    void changed();

    /** Saves the settings, telling the player if it could not. */
    void save();

    /** The version Play launches. */
    default String selectedVersion() {
        return settings().selectedVersion;
    }

    /** A page of the window. */
    interface Page {
        JComponent view();

        /** Read again what the page shows. */
        void refresh();
    }
}
