package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * NeoForge's screen events, fired from the plain game.
 *
 * <p>Mods add buttons to the game's own screens from {@code ScreenEvent.Init}
 * (Create's menu button on the title and pause screens, a config button on the
 * options), swap one screen for another from {@code ScreenEvent.Opening}, draw
 * over screens from {@code ScreenEvent.Render} (JEI's ingredient list) and take
 * clicks and keys from the input events. NeoForge's patched client fires each
 * from a line in {@code Screen}, {@code Gui}, {@code MouseHandler} and {@code
 * KeyboardHandler}; {@code ScreenEventHooks} puts a call to here in each of
 * those places, and this fires NeoForge's event, mostly through NeoForge's own
 * {@code ClientHooks}, as the patch does.
 *
 * <p>Without NeoForge's API jar there is nothing to fire: each call does just
 * what the game's own line did.
 */
public final class NeoScreenEvents {
    private static final OctoLog LOG = OctoLog.of(NeoScreenEvents.class);
    private static final String EVENTS = "net.neoforged.neoforge.client.event.ScreenEvent$";
    private static final Map<ClassLoader, Game> GAMES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private NeoScreenEvents() {
    }

    // ------------------------------------------------------------------- init

    /** {@code this.init()} inside {@code Screen.init(int, int)} and {@code rebuildWidgets()}. */
    public static void init(Object screen) throws Throwable {
        Game game = Game.of(screen);

        if (game.bus == null) {
            invoke(game.screenInit, screen);
            return;
        }

        List<?> children = (List<?>) game.children.get(screen);
        Consumer<Object> add = widget -> add(game, screen, widget);
        Consumer<Object> remove = widget -> {
            try {
                game.removeWidget.invoke(screen, widget);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };

        Object pre = event(game, "Init$Pre", screen, children, add, remove);

        if (pre == null || !canceled(pre)) {
            invoke(game.screenInit, screen);
        }

        event(game, "Init$Post", screen, children, add, remove);
    }

    /** A widget a mod adds to a screen: drawn, narrated and clicked like the screen's own. */
    @SuppressWarnings("unchecked")
    private static void add(Game game, Object screen, Object widget) {
        try {
            if (game.renderable.isInstance(widget)) {
                ((List<Object>) game.renderables.get(screen)).add(widget);
            }

            if (game.narratable.isInstance(widget)) {
                ((List<Object>) game.narratables.get(screen)).add(widget);
            }

            ((List<Object>) game.children.get(screen)).add(widget);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // -------------------------------------------------------------- opening

    /** The start of {@code Gui.setScreen(screen)}: the screen to set, after a mod has had its say. */
    public static Object opening(Object gui, Object screen) {
        try {
            Game game = Game.of(gui);

            if (game.bus == null) {
                return screen;
            }

            Object current = game.currentScreen.invoke(gui);

            if (current != null && current != screen) {
                event(game, "Closing", current);
            }

            if (screen == null || screen == current) {
                return screen;
            }

            Object opening = event(game, "Opening", current, screen);

            if (opening != null) {
                Object replaced = opening.getClass().getMethod("getNewScreen").invoke(opening);
                return replaced != null ? replaced : screen;
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            report("opening", e);
        }

        return screen;
    }

    // --------------------------------------------------------------- render

    /** {@code screen.extractRenderStateWithTooltipAndSubtitles(...)} in {@code Gui}, with the render events. */
    public static void extract(Object screen, Object graphics, int mouseX, int mouseY, float partialTick)
            throws Throwable {
        Game game = Game.of(screen);

        if (game.extractScreen != null) {
            invoke(game.extractScreen, null, screen, new Stack<>(), graphics, mouseX, mouseY, partialTick);
        } else {
            invoke(game.extract, screen, graphics, mouseX, mouseY, partialTick);
        }
    }

    // ---------------------------------------------------------------- input

    public static boolean mouseClicked(Object screen, Object event, boolean doubleClick) throws Throwable {
        Game game = Game.of(screen);

        if (game.hooks("onScreenMouseClickedPre") && (boolean) game.hook("onScreenMouseClickedPre", screen, event,
                doubleClick)) {
            return true;
        }

        boolean handled = (boolean) invoke(game.method("mouseClicked"), screen, event, doubleClick);
        return game.hooks("onScreenMouseClickedPost")
                ? (boolean) game.hook("onScreenMouseClickedPost", screen, event, doubleClick, handled) : handled;
    }

    public static boolean mouseReleased(Object screen, Object event) throws Throwable {
        Game game = Game.of(screen);

        if (game.hooks("onScreenMouseReleasedPre") && (boolean) game.hook("onScreenMouseReleasedPre", screen, event)) {
            return true;
        }

        boolean handled = (boolean) invoke(game.method("mouseReleased"), screen, event);
        return game.hooks("onScreenMouseReleasedPost")
                ? (boolean) game.hook("onScreenMouseReleasedPost", screen, event, handled) : handled;
    }

    public static boolean mouseDragged(Object screen, Object event, double dragX, double dragY) throws Throwable {
        Game game = Game.of(screen);

        if (game.hooks("onScreenMouseDragPre") && (boolean) game.hook("onScreenMouseDragPre", screen, event, dragX,
                dragY)) {
            return true;
        }

        boolean handled = (boolean) invoke(game.method("mouseDragged"), screen, event, dragX, dragY);

        if (game.hooks("onScreenMouseDragPost")) {
            game.hook("onScreenMouseDragPost", screen, event, dragX, dragY);
        }

        return handled;
    }

    public static boolean mouseScrolled(Object screen, double x, double y, double scrollX, double scrollY)
            throws Throwable {
        Game game = Game.of(screen);
        Object mouse = game.hooks("onScreenMouseScrollPre") ? game.mouseHandler() : null;

        if (mouse != null && (boolean) game.hook("onScreenMouseScrollPre", mouse, screen, scrollX, scrollY)) {
            return true;
        }

        boolean handled = (boolean) invoke(game.method("mouseScrolled"), screen, x, y, scrollX, scrollY);

        if (mouse != null && game.hooks("onScreenMouseScrollPost")) {
            game.hook("onScreenMouseScrollPost", mouse, screen, scrollX, scrollY);
        }

        return handled;
    }

    public static boolean keyPressed(Object screen, Object event) throws Throwable {
        Game game = Game.of(screen);

        if (game.hooks("onScreenKeyPressedPre") && (boolean) game.hook("onScreenKeyPressedPre", screen, event)) {
            return true;
        }

        boolean handled = (boolean) invoke(game.method("keyPressed"), screen, event);
        return handled || (game.hooks("onScreenKeyPressedPost")
                && (boolean) game.hook("onScreenKeyPressedPost", screen, event));
    }

    public static boolean keyReleased(Object screen, Object event) throws Throwable {
        Game game = Game.of(screen);

        if (game.hooks("onScreenKeyReleasedPre") && (boolean) game.hook("onScreenKeyReleasedPre", screen, event)) {
            return true;
        }

        boolean handled = (boolean) invoke(game.method("keyReleased"), screen, event);
        return handled || (game.hooks("onScreenKeyReleasedPost")
                && (boolean) game.hook("onScreenKeyReleasedPost", screen, event));
    }

    public static boolean charTyped(Object screen, Object event) throws Throwable {
        Game game = Game.of(screen);

        if (game.hooks("onScreenCharTypedPre") && (boolean) game.hook("onScreenCharTypedPre", screen, event)) {
            return true;
        }

        boolean handled = (boolean) invoke(game.method("charTyped"), screen, event);

        if (!handled && game.hooks("onScreenCharTypedPost")) {
            game.hook("onScreenCharTypedPost", screen, event);
        }

        return handled;
    }

    // -------------------------------------------------------------- helpers

    /** NeoForge's {@code ScreenEvent.<name>}, made and posted; {@code null} when there is none to post. */
    private static Object event(Game game, String name, Object... arguments) {
        try {
            Class<?> type = Class.forName(EVENTS + name, true, game.loader);

            for (java.lang.reflect.Constructor<?> constructor : type.getConstructors()) {
                if (constructor.getParameterCount() == arguments.length) {
                    Object event = constructor.newInstance(arguments);
                    game.post.invoke(game.bus, event);
                    return event;
                }
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            report(name, e);
        }

        return null;
    }

    private static boolean canceled(Object event) {
        try {
            return (boolean) event.getClass().getMethod("isCanceled").invoke(event);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** A method called with the exception it throws, not wrapped. */
    private static Object invoke(Method method, Object target, Object... arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void report(String what, Throwable e) {
        Throwable cause = Failures.unwrap(e);

        if (REPORTED.add(what + ":" + cause.getClass().getName())) {
            LOG.warn("NeoForge's screen event {} could not be fired: {}", what, Failures.describe(cause));
        }
    }

    /** The game's screen classes, and NeoForge's hooks when its API is here. */
    private static final class Game {
        final ClassLoader loader;
        final Method screenInit;
        final Method removeWidget;
        final Method extract;
        final Method currentScreen;
        final Field children;
        final Field renderables;
        final Field narratables;
        final Class<?> renderable;
        final Class<?> narratable;
        final Object bus;
        final Method post;
        final Method extractScreen;
        final Class<?> hooksType;
        final Class<?> screenType;
        final Map<String, Method> methods = new ConcurrentHashMap<>();
        final Map<String, java.util.Optional<Method>> hookMethods = new ConcurrentHashMap<>();

        static Game of(Object anything) {
            return GAMES.computeIfAbsent(anything.getClass().getClassLoader(), Game::new);
        }

        Game(ClassLoader loader) {
            this.loader = loader;

            try {
                screenType = Class.forName("net.minecraft.client.gui.screens.Screen", true, loader);
                screenInit = screenType.getDeclaredMethod("init");
                screenInit.setAccessible(true);
                removeWidget = screenType.getDeclaredMethod("removeWidget",
                        Class.forName("net.minecraft.client.gui.components.events.GuiEventListener", false, loader));
                removeWidget.setAccessible(true);
                Class<?> graphics = Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor", false, loader);
                extract = screenType.getMethod("extractRenderStateWithTooltipAndSubtitles", graphics, int.class,
                        int.class, float.class);
                currentScreen = Class.forName("net.minecraft.client.gui.Gui", true, loader).getMethod("screen");
                children = declaredField(screenType, "children");
                renderables = declaredField(screenType, "renderables");
                narratables = declaredField(screenType, "narratables");
                renderable = Class.forName("net.minecraft.client.gui.components.Renderable", false, loader);
                narratable = Class.forName("net.minecraft.client.gui.narration.NarratableEntry", false, loader);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("this game's screens cannot be bridged", e);
            }

            Object eventBus = null;
            Method postMethod = null;
            Method extractHook = null;
            Class<?> hooks = null;

            try {
                eventBus = Class.forName("net.neoforged.neoforge.common.NeoForge", true, loader).getField("EVENT_BUS")
                        .get(null);
                postMethod = Class.forName("net.neoforged.bus.api.IEventBus", false, loader).getMethod("post",
                        Class.forName("net.neoforged.bus.api.Event", false, loader));
                hooks = Class.forName("net.neoforged.neoforge.client.ClientHooks", true, loader);
                extractHook = hooks.getMethod("extractScreen", screenType, Stack.class,
                        Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor", false, loader), int.class,
                        int.class, float.class);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.debug("NeoForge's screen events are not here: {}", e.toString());
            }

            bus = postMethod == null ? null : eventBus;
            post = postMethod;
            hooksType = hooks;
            extractScreen = bus == null ? null : extractHook;
        }

        private static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        /** The screen's own input method of that name. */
        Method method(String name) {
            return methods.computeIfAbsent(name, key -> {
                for (Method method : screenType.getMethods()) {
                    if (method.getName().equals(key) && method.getReturnType() == boolean.class) {
                        return method;
                    }
                }

                throw new IllegalStateException("Screen." + key + " is not in this game");
            });
        }

        boolean hooks(String name) {
            return bus != null && hookMethod(name) != null;
        }

        private Method hookMethod(String name) {
            return hookMethods.computeIfAbsent(name, key -> {
                if (hooksType == null) {
                    return java.util.Optional.empty();
                }

                for (Method method : hooksType.getMethods()) {
                    if (method.getName().equals(key)) {
                        return java.util.Optional.of(method);
                    }
                }

                return java.util.Optional.empty();
            }).orElse(null);
        }

        Object hook(String name, Object... arguments) throws Throwable {
            return invoke(hookMethod(name), null, arguments);
        }

        Object mouseHandler() {
            try {
                Class<?> client = Class.forName("net.minecraft.client.Minecraft", true, loader);
                Object minecraft = client.getMethod("getInstance").invoke(null);
                Field field = client.getDeclaredField("mouseHandler");
                field.setAccessible(true);
                return field.get(minecraft);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    }
}
