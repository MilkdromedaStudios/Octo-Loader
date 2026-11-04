package studios.milkdromeda.octo.transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Lets an older mod's blocks and items answer a right click.
 *
 * <p>What the game asks a block or an item when it is used has changed its
 * answer twice. A mod's override in the old shape is a method of its own the
 * game never calls: Create's toolbox, schematic table, depot and basin did
 * nothing at all when clicked, and their menus never opened.
 *
 * <ul>
 * <li>{@code Block.useItemOn(...)} answered an {@code ItemInteractionResult}
 * from 1.20.5 to 1.21.1; it answers an {@code InteractionResult} now.</li>
 * <li>{@code Block.use(state, level, pos, player, hand, hit)}, before 1.20.5,
 * became {@code useItemOn} and {@code useWithoutItem}.</li>
 * <li>{@code Item.use(level, player, hand)} answered an
 * {@code InteractionResultHolder} before 1.21.2.</li>
 * </ul>
 *
 * <p>So, as {@link ValueIoBridge} does for saving, the first class in a mod's
 * hierarchy to override an old one is given the one the game calls, which
 * calls the old one — the most derived override of it — and makes its answer
 * into today's with {@link studios.milkdromeda.octo.bridge.Interactions}.
 */
public final class InteractionBridge implements Transformer {
    private static final OctoLog LOG = OctoLog.of(InteractionBridge.class);
    private static final String HELPER = "studios/milkdromeda/octo/bridge/Interactions";
    private static final String RESULT = "Lnet/minecraft/world/InteractionResult;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String PLAYER = "Lnet/minecraft/world/entity/player/Player;";
    private static final String HAND = "Lnet/minecraft/world/InteractionHand;";
    private static final String HIT = "Lnet/minecraft/world/phys/BlockHitResult;";
    private static final String CONVERT = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

    /**
     * An old method, the one the game calls in its place, how the new one's
     * arguments are passed on (an index into its parameters, per old
     * parameter) and the helper method that converts the answer.
     */
    record Bridge(String oldName, String oldDescriptor, String newName, String newDescriptor, int[] arguments,
            String converter) {
    }

    static final List<Bridge> BRIDGES = List.of(
            new Bridge("useItemOn", "(" + STACK + STATE + LEVEL + POS + PLAYER + HAND + HIT
                    + ")Lnet/minecraft/world/ItemInteractionResult;", "useItemOn",
                    "(" + STACK + STATE + LEVEL + POS + PLAYER + HAND + HIT + ")" + RESULT,
                    new int[] { 0, 1, 2, 3, 4, 5, 6 }, "fromItemResult"),
            new Bridge("use", "(" + STATE + LEVEL + POS + PLAYER + HAND + HIT + ")" + RESULT, "useItemOn",
                    "(" + STACK + STATE + LEVEL + POS + PLAYER + HAND + HIT + ")" + RESULT,
                    new int[] { 1, 2, 3, 4, 5, 6 }, "fromBlockUse"),
            new Bridge("use", "(" + LEVEL + PLAYER + HAND + ")Lnet/minecraft/world/InteractionResultHolder;", "use",
                    "(" + LEVEL + PLAYER + HAND + ")" + RESULT, new int[] { 0, 1, 2 }, "fromHolder"));

    /** Only a class naming one of the old answers can override an old method. */
    private static final ByteScan USES = new ByteScan(List.of("ItemInteractionResult", "InteractionResultHolder",
            "BlockHitResult"));

    private final MemberIndex index;

    public InteractionBridge(MemberIndex index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "interaction-bridge";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        String superName = reader.getSuperName();

        if (superName == null || (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0
                || !USES.matches(bytes)) {
            return bytes;
        }

        Set<String> declared = new HashSet<>();
        Map<String, Integer> access = new HashMap<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int methodAccess, String name, String descriptor, String signature,
                    String[] exceptions) {
                declared.add(name + descriptor);

                if ((methodAccess & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0) {
                    access.put(name + descriptor, methodAccess);
                }

                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        List<Bridge> wanted = new ArrayList<>();
        Set<String> written = new HashSet<>();

        for (Bridge bridge : BRIDGES) {
            String old = bridge.oldName() + bridge.oldDescriptor();
            String now = bridge.newName() + bridge.newDescriptor();

            if (access.containsKey(old) && !declared.contains(now) && written.add(now)
                    // The game calls the new one...
                    && index.isDeclaredFrom(superName, bridge.newName(), bridge.newDescriptor(),
                            InteractionBridge::isGame)
                    // ...and no class of the mod's above this one already answers it.
                    && !index.isDeclaredFrom(superName, bridge.oldName(), bridge.oldDescriptor(),
                            type -> !isGame(type))) {
                wanted.add(bridge);
            }
        }

        if (wanted.isEmpty()) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                for (Bridge bridge : wanted) {
                    write(cv, className, bridge, access.get(bridge.oldName() + bridge.oldDescriptor()));
                }

                super.visitEnd();
            }
        }, 0);

        LOG.debug("{} answers right clicks as this Minecraft asks", className.replace('/', '.'));
        return writer.toByteArray();
    }

    private static void write(ClassVisitor writer, String className, Bridge bridge, int oldAccess) {
        int visibility = oldAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
        MethodVisitor method = writer.visitMethod(visibility == 0 ? Opcodes.ACC_PROTECTED : visibility,
                bridge.newName(), bridge.newDescriptor(), null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 0);

        Type[] parameters = Type.getArgumentTypes(bridge.newDescriptor());
        int[] slots = new int[parameters.length];

        for (int i = 0, slot = 1; i < parameters.length; i++) {
            slots[i] = slot;
            slot += parameters[i].getSize();
        }

        for (int argument : bridge.arguments()) {
            method.visitVarInsn(parameters[argument].getOpcode(Opcodes.ILOAD), slots[argument]);
        }

        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, bridge.oldName(), bridge.oldDescriptor(), false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, bridge.converter(), CONVERT, false);
        method.visitTypeInsn(Opcodes.CHECKCAST, Type.getReturnType(bridge.newDescriptor()).getInternalName());
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static boolean isGame(String type) {
        return type.startsWith("net/minecraft/") || type.startsWith("com/mojang/");
    }
}
