package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Mods' own network packets, carried by the unpatched game.
 *
 * <p>A NeoForge mod registers each of its packets — its type, the codec that
 * writes it, the handler that acts on it — through {@code
 * RegisterPayloadHandlersEvent}, and NeoForge's patched game consults that
 * table as it writes and reads custom payloads. This game knows only its own
 * payloads: the first packet a mod sent was written with the codec for
 * payloads it does not know, failed, and dropped the player from the world.
 *
 * <p>NeoForge's own {@code NetworkRegistry} still fills its tables when told
 * to ({@link #setup}); {@code PayloadHooks} asks it here for a codec where the
 * game finds none of its own, and hands a received mod payload to the mod's
 * handler, with a context answered from the game's own packet listener.
 */
public final class ModPayloads {
    private static final OctoLog LOG = OctoLog.of(ModPayloads.class);
    private static final String REGISTRY = "net.neoforged.neoforge.network.registration.NetworkRegistry";

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static volatile Class<?> registry;

    private ModPayloads() {
    }

    /** Has the mods register their packets, as NeoForge does once they are loaded. */
    public static void setup(ClassLoader loader) {
        try {
            if (loader.getResource(REGISTRY.replace('.', '/') + ".class") == null) {
                return;
            }

            Class<?> type = Class.forName(REGISTRY, true, loader);
            type.getMethod("setup").invoke(null);
            registry = type;
            LOG.info("{} mod packet type(s) registered", count(type));
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("mods' packets could not be registered: {}", Failures.describe(Failures.unwrap(e)));
            OctoLog.detail(Failures.unwrap(e));
        }
    }

    /**
     * The extra spawn data of an entity that carries some, sent with the rest
     * of what a player is told about an entity coming into view, as NeoForge's
     * patched {@code ServerEntity} does. Without it Create's contraptions
     * reached the client with no blocks in them, and were never drawn.
     */
    @SuppressWarnings("unchecked")
    public static void pairing(Object serverEntity, Object consumer) {
        try {
            Object entity = entityOf(serverEntity);
            ClassLoader loader = serverEntity.getClass().getClassLoader();

            if (entity == null || !Class.forName("net.neoforged.neoforge.entity.IEntityWithComplexSpawn", false,
                    loader).isInstance(entity)) {
                return;
            }

            // The spawn data written here, as NeoForge's payload would: its own
            // writer wants a connection type this game's buffers do not carry.
            Object buffer = spawnBuffer(loader, entity, Class.forName("io.netty.buffer.Unpooled", true, loader)
                    .getMethod("buffer").invoke(null));
            entity.getClass().getMethod("writeSpawnData", buffer.getClass()).invoke(entity, buffer);
            int written = (int) buffer.getClass().getMethod("writerIndex").invoke(buffer);
            byte[] data = new byte[written];
            buffer.getClass().getMethod("getBytes", int.class, byte[].class).invoke(buffer, 0, data);
            int id = (int) entity.getClass().getMethod("getId").invoke(entity);
            Object payload = Class.forName("net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload", true,
                    loader).getConstructor(int.class, byte[].class).newInstance(id, data);
            Class<?> payloadType = Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload",
                    false, loader);
            Object packet = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket",
                    true, loader).getConstructor(payloadType).newInstance(payload);
            ((java.util.function.Consumer<Object>) consumer).accept(packet);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            report("spawn", Failures.unwrap(e));
        }
    }

    private static volatile Object[] spawnType;

    /** {@code AdvancedAddEntityPayload.STREAM_CODEC} when {@code id} is its type's id. */
    private static Object spawnCodec(ClassLoader loader, Object id) {
        try {
            Object[] known = spawnType;

            if (known == null) {
                Class<?> payload = Class.forName("net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload",
                        true, loader);
                Object payloadType = payload.getField("TYPE").get(null);
                known = new Object[] { payloadType.getClass().getMethod("id").invoke(payloadType),
                        payload.getField("STREAM_CODEC").get(null) };
                spawnType = known;
            }

            return known[0].equals(id) ? known[1] : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /** {@code new RegistryFriendlyByteBuf(bytes, entity.registryAccess())}. */
    private static Object spawnBuffer(ClassLoader loader, Object entity, Object bytes)
            throws ReflectiveOperationException {
        Object registries = entity.getClass().getMethod("registryAccess").invoke(entity);
        Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf", false, loader);
        Class<?> registryAccess = Class.forName("net.minecraft.core.RegistryAccess", false, loader);
        return Class.forName("net.minecraft.network.RegistryFriendlyByteBuf", true, loader)
                .getConstructor(byteBuf, registryAccess).newInstance(bytes, registries);
    }

    /** An entity's extra spawn data arriving: read into the entity the client already made. */
    private static boolean spawnData(Object listener, Object payload) throws Exception {
        int id = (int) payload.getClass().getMethod("entityId").invoke(payload);
        byte[] data = (byte[]) payload.getClass().getMethod("customPayload").invoke(payload);
        Object minecraft = field(listener, "minecraft");
        ClassLoader loader = listener.getClass().getClassLoader();

        enqueue(minecraft, (Runnable) () -> {
            try {
                Object level = field(minecraft, "level");
                Object entity = level == null ? null : level.getClass().getMethod("getEntity", int.class)
                        .invoke(level, id);

                if (entity == null) {
                    return;
                }

                Object buffer = spawnBuffer(loader, entity, Class.forName("io.netty.buffer.Unpooled", true, loader)
                        .getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) data));
                entity.getClass().getMethod("readSpawnData", buffer.getClass()).invoke(entity, buffer);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                report("spawn", Failures.unwrap(e));
            }
        });
        return true;
    }

    private static Object entityOf(Object serverEntity) throws ReflectiveOperationException {
        Field field = ENTITY_FIELD;

        if (field == null) {
            field = serverEntity.getClass().getDeclaredField("entity");
            field.setAccessible(true);
            ENTITY_FIELD = field;
        }

        return field.get(serverEntity);
    }

    private static volatile Field ENTITY_FIELD;

    private static int count(Class<?> type) throws ReflectiveOperationException {
        int total = 0;

        for (Object byId : registrations(type).values()) {
            total += ((Map<?, ?>) byId).size();
        }

        return total;
    }

    // ------------------------------------------------------------------ codec

    /**
     * {@code CustomPacketPayload}'s codec, asked for a payload id: the codec
     * the mod registered for it, or {@code null} for the game's own lookup —
     * which always comes first, so no mod's id displaces one of the game's.
     */
    public static Object codec(Map<?, ?> gameCodecs, Object id) {
        Class<?> type = registry;

        if (type == null || id == null || gameCodecs.containsKey(id)) {
            return null;
        }

        // NeoForge's own entity spawn data, which Octo sends itself (pairing).
        Object spawn = spawnCodec(type.getClassLoader(), id);

        if (spawn != null) {
            return spawn;
        }

        try {
            for (Object byId : registrations(type).values()) {
                Object registration = ((Map<?, ?>) byId).get(id);

                if (registration != null) {
                    return registration.getClass().getMethod("codec").invoke(registration);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            report("codec", e);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> registrations(Class<?> type) throws ReflectiveOperationException {
        Field field = type.getDeclaredField("PAYLOAD_REGISTRATIONS");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }

    // -------------------------------------------------------------- receiving

    /**
     * A custom payload packet arriving at a packet listener: handled here and
     * consumed when it is a mod's, left to the game when it is not.
     *
     * @param clientbound whether the listener is the client's
     */
    public static boolean handle(Object listener, Object packet, boolean clientbound) {
        Class<?> type = registry;

        if (type == null || packet == null) {
            return false;
        }

        try {
            Object payload = packet.getClass().getMethod("payload").invoke(packet);

            if (clientbound && payload.getClass().getName()
                    .equals("net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload")) {
                return spawnData(listener, payload);
            }

            Object payloadType = payload.getClass().getMethod("type").invoke(payload);
            Object id = payloadType.getClass().getMethod("id").invoke(payloadType);
            Object handler = handler(type, clientbound ? "CLIENTBOUND_HANDLERS" : "SERVERBOUND_HANDLERS", id);

            if (handler == null) {
                return false;
            }

            Object context = context(listener, clientbound, handler);
            ClassLoader loader = listener.getClass().getClassLoader();
            Method handle = Class.forName("net.neoforged.neoforge.network.handling.IPayloadHandler", false, loader)
                    .getMethod("handle",
                            Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload", false,
                                    loader),
                            Class.forName("net.neoforged.neoforge.network.handling.IPayloadContext", false, loader));
            handle.invoke(handler, payload, context);
            return true;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            report("handle", Failures.unwrap(e));
            return false;
        }
    }

    private static Object handler(Class<?> type, String table, Object id) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(table);
        field.setAccessible(true);

        for (Object byId : ((Map<?, ?>) field.get(null)).values()) {
            Object handler = ((Map<?, ?>) byId).get(id);

            if (handler != null) {
                return handler;
            }
        }

        return null;
    }

    /** NeoForge's handler context, answered from the game's packet listener. */
    private static Object context(Object listener, boolean clientbound, Object handler) throws Exception {
        ClassLoader loader = listener.getClass().getClassLoader();
        Class<?> contextType = Class.forName("net.neoforged.neoforge.network.handling.IPayloadContext", true, loader);
        Object executor = field(listener, clientbound ? "minecraft" : "server");

        return Proxy.newProxyInstance(loader, new Class<?>[] { contextType }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "listener" -> {
                    return listener;
                }
                case "connection" -> {
                    return field(listener, "connection");
                }
                case "player" -> {
                    return clientbound ? field(executor, "player") : field(listener, "player");
                }
                case "flow" -> {
                    return constant(loader, "net.minecraft.network.protocol.PacketFlow",
                            clientbound ? "CLIENTBOUND" : "SERVERBOUND");
                }
                case "protocol" -> {
                    return constant(loader, "net.minecraft.network.ConnectionProtocol", "PLAY");
                }
                case "enqueueWork" -> {
                    return enqueue(executor, args[0]);
                }
                case "reply" -> {
                    reply(listener, clientbound, args[0]);
                    return null;
                }
                case "handle" -> {
                    return null;
                }
                case "disconnect", "finishCurrentTask" -> {
                    return null;
                }
                case "channelHandlerContext" -> {
                    return null;
                }
                case "toString" -> {
                    return "Octo payload context for " + listener;
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                default -> {
                    return null;
                }
            }
        });
    }

    /** Work handed to the game's own thread, as NeoForge's context does. */
    private static CompletableFuture<Object> enqueue(Object executor, Object work) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(work instanceof Supplier<?> supplier ? supplier.get() : run((Runnable) work));
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                report("work", e);
                future.completeExceptionally(e);
            }
        };

        try {
            executor.getClass().getMethod("execute", Runnable.class).invoke(executor, task);
        } catch (ReflectiveOperationException e) {
            task.run();
        }

        return future;
    }

    private static Object run(Runnable work) {
        work.run();
        return null;
    }

    private static void reply(Object listener, boolean clientbound, Object payload) throws Exception {
        ClassLoader loader = listener.getClass().getClassLoader();
        Class<?> payloadType = Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload", false,
                loader);
        Class<?> packetType = Class.forName("net.minecraft.network.protocol.common."
                + (clientbound ? "Serverbound" : "Clientbound") + "CustomPayloadPacket", true, loader);
        Object packet = packetType.getConstructor(payloadType).newInstance(payload);
        listener.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet", false, loader))
                .invoke(listener, packet);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException e) {
                // Declared higher up.
            }
        }

        throw new NoSuchFieldException(owner.getClass().getName() + "." + name);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object constant(ClassLoader loader, String type, String name) throws ClassNotFoundException {
        return Enum.valueOf((Class<? extends Enum>) Class.forName(type, true, loader), name);
    }

    private static void report(String what, Throwable e) {
        if (REPORTED.add(what + ":" + e.getClass().getName() + ":" + e.getMessage())) {
            LOG.warn("a mod's packet could not be {}: {}", what.equals("codec") ? "written" : "handled",
                    Failures.describe(e));
            OctoLog.detail(e);
        }
    }
}
