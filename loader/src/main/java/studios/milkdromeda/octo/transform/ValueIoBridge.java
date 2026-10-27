package studios.milkdromeda.octo.transform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Lets a block entity or an entity from before 1.21.6 save and load.
 *
 * <p>The game calls {@code saveAdditional(ValueOutput)} and
 * {@code loadAdditional(ValueInput)} now; a mod written earlier overrides
 * {@code saveAdditional(CompoundTag, HolderLookup.Provider)}, which nothing
 * calls any more. Create's machines kept nothing: not their speed, not what a
 * press was pressing, not a tank's fluid — on every save of the world.
 *
 * <p>So the first class in a mod's hierarchy to override one of the old
 * methods is given the new one, which the game does call: it lets the game's
 * own superclass do its part, then calls the old method — the most derived
 * override of it, which calls up its own chain as it always did — with a tag
 * {@link studios.milkdromeda.octo.bridge.ValueIo} makes from the new
 * argument. Only the first class is given it, so nothing is saved or loaded
 * twice.
 */
public final class ValueIoBridge implements Transformer {
    private static final OctoLog LOG = OctoLog.of(ValueIoBridge.class);
    private static final String VALUE_IO = "studios/milkdromeda/octo/bridge/ValueIo";
    private static final String TAG = "net/minecraft/nbt/CompoundTag";
    private static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String OUTPUT = "net/minecraft/world/level/storage/ValueOutput";
    private static final String INPUT = "net/minecraft/world/level/storage/ValueInput";

    /** An old method, and the one the game calls in its place. */
    private record Bridge(String name, String oldDescriptor, String newDescriptor, boolean save,
            boolean withRegistries) {
    }

    private static final List<Bridge> BRIDGES = List.of(
            new Bridge("saveAdditional", "(L" + TAG + ";L" + PROVIDER + ";)V", "(L" + OUTPUT + ";)V", true, true),
            new Bridge("loadAdditional", "(L" + TAG + ";L" + PROVIDER + ";)V", "(L" + INPUT + ";)V", false, true),
            new Bridge("addAdditionalSaveData", "(L" + TAG + ";)V", "(L" + OUTPUT + ";)V", true, false),
            new Bridge("readAdditionalSaveData", "(L" + TAG + ";)V", "(L" + INPUT + ";)V", false, false));

    private final MemberIndex index;

    public ValueIoBridge(MemberIndex index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "value-io-bridge";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        String superName = reader.getSuperName();

        if (superName == null || (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) {
            return bytes;
        }

        Set<String> declared = new HashSet<>();
        int[] access = new int[BRIDGES.size()];

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int methodAccess, String name, String descriptor, String signature,
                    String[] exceptions) {
                declared.add(name + descriptor);

                for (int i = 0; i < BRIDGES.size(); i++) {
                    Bridge bridge = BRIDGES.get(i);

                    if ((methodAccess & Opcodes.ACC_STATIC) == 0 && name.equals(bridge.name())
                            && descriptor.equals(bridge.oldDescriptor())) {
                        access[i] = methodAccess;
                    }
                }

                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        List<Bridge> wanted = new ArrayList<>();
        List<Integer> accesses = new ArrayList<>();

        for (int i = 0; i < BRIDGES.size(); i++) {
            Bridge bridge = BRIDGES.get(i);

            if (declared.contains(bridge.name() + bridge.oldDescriptor())
                    && !declared.contains(bridge.name() + bridge.newDescriptor())
                    // The game calls the new one...
                    && index.isDeclaredFrom(superName, bridge.name(), bridge.newDescriptor(), ValueIoBridge::isGame)
                    // ...and no class of the mod's above this one already answers it.
                    && !index.isDeclaredFrom(superName, bridge.name(), bridge.oldDescriptor(), type -> !isGame(type))) {
                wanted.add(bridge);
                accesses.add(access[i]);
            }
        }

        if (wanted.isEmpty()) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                for (int i = 0; i < wanted.size(); i++) {
                    write(cv, className, superName, wanted.get(i), accesses.get(i));
                }

                super.visitEnd();
            }
        }, 0);

        LOG.debug("{} saves and loads through this Minecraft's ValueOutput and ValueInput", className.replace('/', '.'));
        return writer.toByteArray();
    }

    private void write(ClassVisitor writer, String className, String superName, Bridge bridge, int oldAccess) {
        int access = oldAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
        MethodVisitor method = writer.visitMethod(access == 0 ? Opcodes.ACC_PROTECTED : access, bridge.name(),
                bridge.newDescriptor(), null, null);
        method.visitCode();

        // The game's superclass first, as the old method once called it: a
        // container's items, an entity's health. Not where it is abstract.
        Integer superAccess = index.methodAccess(superName, bridge.name(), bridge.newDescriptor());

        if (superAccess != null && (superAccess & Opcodes.ACC_ABSTRACT) == 0) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, bridge.name(), bridge.newDescriptor(), false);
        }

        if (bridge.save()) {
            method.visitTypeInsn(Opcodes.NEW, TAG);
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, TAG, "<init>", "()V", false);
            method.visitVarInsn(Opcodes.ASTORE, 2);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 2);

            if (bridge.withRegistries()) {
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKESTATIC, VALUE_IO, "registries",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                method.visitTypeInsn(Opcodes.CHECKCAST, PROVIDER);
            }

            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, bridge.name(), bridge.oldDescriptor(), false);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitVarInsn(Opcodes.ALOAD, 2);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, VALUE_IO, "store", "(Ljava/lang/Object;Ljava/lang/Object;)V",
                    false);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, VALUE_IO, "tag", "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            method.visitTypeInsn(Opcodes.CHECKCAST, TAG);

            if (bridge.withRegistries()) {
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKEINTERFACE, INPUT, "lookup", "()L" + PROVIDER + ";", true);
            }

            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, bridge.name(), bridge.oldDescriptor(), false);
        }

        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static boolean isGame(String type) {
        return type.startsWith("net/minecraft/") || type.startsWith("com/mojang/");
    }
}
