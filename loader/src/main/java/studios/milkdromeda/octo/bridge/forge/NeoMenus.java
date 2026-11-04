package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.OptionalInt;
import java.util.function.Consumer;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Menus opened with extra data, as NeoForge's patched game opens them.
 *
 * <p>A NeoForge mod opens its menus with {@code player.openMenu(provider,
 * buffer -> ...)} (or {@code openMenu(provider, pos)}), writing what the
 * client needs to build the menu: Create's schematic table its contents, its
 * toolbox where it stands. The method comes from NeoForge's player interface,
 * whose own version does nothing; the patch to {@code ServerPlayer} is what
 * opened the menu. So on this game every such menu stayed shut. Here the menu
 * is opened as the game opens any other, and NeoForge's own open-screen
 * payload carries the extra data to the client, which builds the menu from it
 * with the mod's factory and shows its screen.
 */
public final class NeoMenus {
    private static final OctoLog LOG = OctoLog.of(NeoMenus.class);
    private static final String PAYLOAD = "net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload";

    private NeoMenus() {
    }

    // ----------------------------------------------------------------- server

    /** {@code Player.openMenu(provider, extraData)}: on the server, opens it; on a client, nothing (as NeoForge). */
    @SuppressWarnings("unchecked")
    public static OptionalInt open(Object player, Object provider, Object writer) {
        if (provider == null || player == null) {
            return OptionalInt.empty();
        }

        ClassLoader loader = player.getClass().getClassLoader();

        try {
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer", false, loader);

            if (!serverPlayer.isInstance(player)) {
                return OptionalInt.empty();
            }

            Class<?> providerType = Class.forName("net.minecraft.world.MenuProvider", false, loader);

            if (writer == null) {
                return (OptionalInt) serverPlayer.getMethod("openMenu", providerType).invoke(player, provider);
            }

            // As the game's own openMenu: close what is open, take the next id, make the menu.
            Object current = field(player, "containerMenu");
            Object inventoryMenu = field(player, "inventoryMenu");

            if (current != inventoryMenu) {
                serverPlayer.getMethod("closeContainer").invoke(player);
            }

            declared(serverPlayer, "nextContainerCounter").invoke(player);
            int counter = (int) field(player, "containerCounter");
            Object inventory = serverPlayer.getMethod("getInventory").invoke(player);
            Class<?> inventoryType = Class.forName("net.minecraft.world.entity.player.Inventory", false, loader);
            Class<?> playerType = Class.forName("net.minecraft.world.entity.player.Player", false, loader);
            Object menu = providerType.getMethod("createMenu", int.class, inventoryType, playerType)
                    .invoke(provider, counter, inventory, player);

            if (menu == null) {
                return OptionalInt.empty();
            }

            // The extra data, written by the mod into a buffer of this player's registries.
            Object buffer = buffer(loader, player, Class.forName("io.netty.buffer.Unpooled", true, loader)
                    .getMethod("buffer").invoke(null));
            ((Consumer<Object>) writer).accept(buffer);
            int written = (int) buffer.getClass().getMethod("writerIndex").invoke(buffer);
            byte[] data = new byte[written];
            buffer.getClass().getMethod("getBytes", int.class, byte[].class).invoke(buffer, 0, data);
            buffer.getClass().getMethod("release").invoke(buffer);

            Class<?> menuClass = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu", false, loader);
            int containerId = menuClass.getField("containerId").getInt(menu);
            Object menuType = menuClass.getMethod("getType").invoke(menu);
            Object title = providerType.getMethod("getDisplayName").invoke(provider);
            Object payload = Class.forName(PAYLOAD, true, loader).getConstructor(int.class,
                    Class.forName("net.minecraft.world.inventory.MenuType", false, loader),
                    Class.forName("net.minecraft.network.chat.Component", false, loader), byte[].class)
                    .newInstance(containerId, menuType, title, data);
            send(loader, player, payload);

            declared(serverPlayer, "initMenu", menuClass).invoke(player, menu);
            setField(player, "containerMenu", menu);
            postOpen(loader, player, menu);
            return OptionalInt.of(counter);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("a menu could not be opened with its extra data: {}", Failures.describe(Failures.unwrap(e)));
            OctoLog.detail(Failures.unwrap(e));
            return OptionalInt.empty();
        }
    }

    /** {@code Player.openMenu(provider, pos)}: the position as the extra data, as NeoForge writes it. */
    public static OptionalInt openAt(Object player, Object provider, Object pos) {
        Consumer<Object> writer = buffer -> {
            try {
                Method write = null;

                for (Method method : buffer.getClass().getMethods()) {
                    if (method.getName().equals("writeBlockPos") && method.getParameterCount() == 1) {
                        write = method;
                    }
                }

                write.invoke(buffer, pos);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };
        return open(player, provider, writer);
    }

    private static void send(ClassLoader loader, Object player, Object payload) throws ReflectiveOperationException {
        Class<?> payloadType = Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload", false,
                loader);
        Object packet = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket", true,
                loader).getConstructor(payloadType).newInstance(payload);
        Object connection = field(player, "connection");
        connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet", false, loader))
                .invoke(connection, packet);
    }

    private static void postOpen(ClassLoader loader, Object player, Object menu) {
        try {
            Class<?> event = Class.forName("net.neoforged.neoforge.event.entity.player.PlayerContainerEvent$Open",
                    true, loader);
            Object instance = event.getConstructors()[0].newInstance(player, menu);
            Object bus = Class.forName("net.neoforged.neoforge.common.NeoForge", true, loader).getField("EVENT_BUS")
                    .get(null);
            Class<?> eventType = Class.forName("net.neoforged.bus.api.Event", false, loader);
            bus.getClass().getMethod("post", eventType).invoke(bus, instance);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
        }
    }

    // ----------------------------------------------------------------- client

    /** Whether a payload is NeoForge's open-screen payload, which {@link #openScreen} handles. */
    static boolean isOpenScreen(Object payload) {
        return payload != null && payload.getClass().getName().equals(PAYLOAD);
    }

    /** The open-screen payload on the client: the menu built from its extra data, and its screen shown. */
    static void openScreen(Object minecraft, Object payload) throws ReflectiveOperationException {
        ClassLoader loader = payload.getClass().getClassLoader();
        int windowId = (int) payload.getClass().getMethod("windowId").invoke(payload);
        Object menuType = payload.getClass().getMethod("menuType").invoke(payload);
        Object title = payload.getClass().getMethod("name").invoke(payload);
        byte[] data = (byte[]) payload.getClass().getMethod("additionalData").invoke(payload);
        Object player = field(minecraft, "player");

        if (player == null) {
            return;
        }

        Object inventory = player.getClass().getMethod("getInventory").invoke(player);
        Object buffer = buffer(loader, player, Class.forName("io.netty.buffer.Unpooled", true, loader)
                .getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) data));

        try {
            Object menu = create(menuType, windowId, inventory, buffer);
            Class<?> screens = Class.forName("net.minecraft.client.gui.screens.MenuScreens", true, loader);
            Method getConstructor = declared(screens, "getConstructor",
                    Class.forName("net.minecraft.world.inventory.MenuType", false, loader));
            Object constructor = getConstructor.invoke(null, menuType);

            if (constructor == null || menu == null) {
                LOG.warn("no screen is registered for the menu {}; it stays shut", menuType);
                return;
            }

            Method make = null;

            for (Method method : constructor.getClass().getInterfaces()[0].getMethods()) {
                if (method.getName().equals("create") && method.getParameterCount() == 3) {
                    make = method;
                }
            }

            make.setAccessible(true);
            Object screen = make.invoke(constructor, menu, inventory, title);
            Object shownMenu = screen.getClass().getMethod("getMenu").invoke(screen);
            setField(player, "containerMenu", shownMenu);
            Object gui = field(minecraft, "gui");
            gui.getClass().getMethod("setScreen", Class.forName("net.minecraft.client.gui.screens.Screen", false,
                    loader)).invoke(gui, screen);
        } finally {
            buffer.getClass().getMethod("release").invoke(buffer);
        }
    }

    /**
     * {@code MenuType.create(id, inventory, extraData)}, NeoForge's: the mod's
     * factory reading the extra data, or the plain one for a menu without one.
     */
    public static Object create(Object menuType, int windowId, Object inventory, Object buffer) {
        try {
            Object supplier = field(menuType, "constructor");
            ClassLoader loader = menuType.getClass().getClassLoader();
            Class<?> factory = Class.forName("net.neoforged.neoforge.network.IContainerFactory", false, loader);
            Class<?> inventoryType = Class.forName("net.minecraft.world.entity.player.Inventory", false, loader);

            if (buffer != null && factory.isInstance(supplier)) {
                return factory.getMethod("create", int.class, inventoryType,
                        Class.forName("net.minecraft.network.RegistryFriendlyByteBuf", false, loader))
                        .invoke(supplier, windowId, inventory, buffer);
            }

            return menuType.getClass().getMethod("create", int.class, inventoryType).invoke(menuType, windowId,
                    inventory);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("a menu could not be made", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable e) throws T {
        throw (T) e;
    }

    // ----------------------------------------------------------------- helpers

    /** {@code new RegistryFriendlyByteBuf(bytes, player.registryAccess())}. */
    private static Object buffer(ClassLoader loader, Object player, Object bytes) throws ReflectiveOperationException {
        Object registries = player.getClass().getMethod("registryAccess").invoke(player);
        Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf", false, loader);
        Class<?> registryAccess = Class.forName("net.minecraft.core.RegistryAccess", false, loader);
        return Class.forName("net.minecraft.network.RegistryFriendlyByteBuf", true, loader)
                .getConstructor(byteBuf, registryAccess).newInstance(bytes, registries);
    }

    private static Method declared(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Method method = at.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                // Higher up.
            }
        }

        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Field fieldOf(Object owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // Higher up.
            }
        }

        throw new NoSuchFieldException(owner.getClass().getName() + "." + name);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        return fieldOf(owner, name).get(owner);
    }

    private static void setField(Object owner, String name, Object value) throws ReflectiveOperationException {
        fieldOf(owner, name).set(owner, value);
    }
}
