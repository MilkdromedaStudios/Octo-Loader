package studios.milkdromeda.octo.transform;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Where NeoForge's patched game fires its game events, a call that fires
 * them here ({@link studios.milkdromeda.octo.bridge.forge.NeoGameEvents}).
 *
 * <p>Each point is a game method and whether the call goes where it starts
 * or before each of its returns, with what the method holds passed along:
 * the client's tick, the server's, each level's and each player's; the
 * server starting and stopping; the command dispatcher being built; a level
 * made or closed; a player joining or leaving.
 */
public final class GameEventHooks implements Transformer {
    private static final String HELPER = "studios/milkdromeda/octo/bridge/forge/NeoGameEvents";

    /**
     * One call into the helper.
     *
     * @param atEnd  before each return rather than at the start
     * @param loads  the local slots passed, {@code this} being 0
     * @param pre    a trailing boolean, for the hooks that serve both ends; null for none
     */
    record Point(String owner, String name, String descriptor, boolean atEnd, String hook, int[] loads,
            Boolean pre) {
        String hookDescriptor() {
            return "(" + "Ljava/lang/Object;".repeat(loads.length) + (pre == null ? "" : "Z") + ")V";
        }
    }

    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String SERVER = "net/minecraft/server/MinecraftServer";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    private static final String TICK_DESC = "(Ljava/util/function/BooleanSupplier;)V";

    static final List<Point> POINTS = List.of(
            new Point(MINECRAFT, "tick", "()V", false, "clientTick", new int[] { 0 }, true),
            new Point(MINECRAFT, "tick", "()V", true, "clientTick", new int[] { 0 }, false),
            new Point(SERVER, "tickServer", TICK_DESC, false, "serverTick", new int[] { 0, 1 }, true),
            new Point(SERVER, "tickServer", TICK_DESC, true, "serverTick", new int[] { 0, 1 }, false),
            new Point(SERVER_LEVEL, "tick", TICK_DESC, false, "levelTick", new int[] { 0, 1 }, true),
            new Point(SERVER_LEVEL, "tick", TICK_DESC, true, "levelTick", new int[] { 0, 1 }, false),
            new Point(CLIENT_LEVEL, "tick", TICK_DESC, false, "levelTick", new int[] { 0, 1 }, true),
            new Point(CLIENT_LEVEL, "tick", TICK_DESC, true, "levelTick", new int[] { 0, 1 }, false),
            new Point("net/minecraft/world/entity/player/Player", "tick", "()V", false, "playerTick",
                    new int[] { 0 }, true),
            new Point("net/minecraft/world/entity/player/Player", "tick", "()V", true, "playerTick",
                    new int[] { 0 }, false),
            new Point(SERVER, "runServer", "()V", false, "serverAboutToStart", new int[] { 0 }, null),
            new Point(SERVER, "loadLevel", "()V", true, "serverStarting", new int[] { 0 }, null),
            new Point(SERVER, "stopServer", "()V", false, "serverStopping", new int[] { 0 }, null),
            new Point(SERVER, "stopServer", "()V", true, "serverStopped", new int[] { 0 }, null),
            new Point("net/minecraft/commands/Commands", "<init>",
                    "(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V",
                    true, "registerCommands", new int[] { 0, 1, 2 }, null),
            new Point(SERVER_LEVEL, "<init>", null, true, "levelLoad", new int[] { 0 }, null),
            new Point(SERVER_LEVEL, "close", "()V", false, "levelUnload", new int[] { 0 }, null),
            new Point(CLIENT_LEVEL, "<init>", null, true, "levelLoad", new int[] { 0 }, null),
            new Point("net/minecraft/server/players/PlayerList", "placeNewPlayer", null, true, "playerLoggedIn",
                    new int[] { 2 }, null),
            new Point("net/minecraft/server/players/PlayerList", "remove",
                    "(Lnet/minecraft/server/level/ServerPlayer;)V", false, "playerLoggedOut", new int[] { 1 }, null));

    private static final Map<String, List<Point>> BY_OWNER = POINTS.stream()
            .collect(Collectors.groupingBy(Point::owner));

    @Override
    public String name() {
        return "game-event-hooks";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return BY_OWNER.containsKey(className);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        List<Point> points = BY_OWNER.get(className);

        if (points == null) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (delegate == null || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
                    return delegate;
                }

                List<Point> here = points.stream()
                        .filter(point -> point.name().equals(name)
                                && (point.descriptor() == null || point.descriptor().equals(descriptor)))
                        .filter(point -> fits(point, descriptor))
                        .toList();

                if (here.isEmpty()) {
                    return delegate;
                }

                changed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();

                        // A constructor's start is before its super call; only
                        // its ends are hooked.
                        if (!name.equals("<init>")) {
                            here.stream().filter(point -> !point.atEnd()).forEach(this::call);
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            here.stream().filter(Point::atEnd).forEach(this::call);
                        }

                        super.visitInsn(opcode);
                    }

                    private void call(Point point) {
                        for (int slot : point.loads()) {
                            super.visitVarInsn(Opcodes.ALOAD, slot);
                        }

                        if (point.pre() != null) {
                            super.visitInsn(point.pre() ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                        }

                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, point.hook(), point.hookDescriptor(),
                                false);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }

    /** Whether each slot a point passes holds a reference in a method of this descriptor. */
    private static boolean fits(Point point, String descriptor) {
        org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        int[] slotSorts = new int[64];
        java.util.Arrays.fill(slotSorts, -1);
        slotSorts[0] = org.objectweb.asm.Type.OBJECT;
        int slot = 1;

        for (org.objectweb.asm.Type argument : arguments) {
            if (slot < slotSorts.length) {
                slotSorts[slot] = argument.getSort();
            }

            slot += argument.getSize();
        }

        for (int load : point.loads()) {
            int sort = load < slotSorts.length ? slotSorts[load] : -1;

            if (sort != org.objectweb.asm.Type.OBJECT && sort != org.objectweb.asm.Type.ARRAY) {
                return false;
            }
        }

        return true;
    }
}
