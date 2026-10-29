package studios.milkdromeda.octo.transform;

import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Answers a class added back from an older release with this game's own
 * machinery, where its old code cannot work at all.
 *
 * <p>Most classes added back for a mod work as they did: a shape the mod
 * expects, built on the parts of the game that are still there. The block
 * renderer is not one of those: it is a front for a model system 26.x
 * replaced whole, and its old code reaches for model shapers and caches that
 * no longer exist. So each of its methods, and its model renderer's, hands
 * its arguments to {@link studios.milkdromeda.octo.bridge.client.LegacyModels},
 * which does the same work with 26.x's models. The class keeps its shape,
 * which is what the mod links against.
 */
public final class RestoredBridges {
    private static final String HELPER = "studios/milkdromeda/octo/bridge/client/LegacyModels";

    private static final Set<String> BRIDGED = Set.of(
            "net/minecraft/client/renderer/block/BlockRenderDispatcher",
            "net/minecraft/client/renderer/block/ModelBlockRenderer",
            "net/minecraft/client/renderer/block/BlockModelShaper");

    private RestoredBridges() {
    }

    public static boolean bridges(String className) {
        return BRIDGED.contains(className);
    }

    /** The class with every method but its constructors answered by the helper. */
    public static byte[] transform(String className, byte[] bytes) {
        if (!bridges(className)) {
            return bytes;
        }

        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);

        for (MethodNode method : node.methods) {
            if (method.name.startsWith("<") || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || (method.access & Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }

            answer(className, method);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void answer(String owner, MethodNode method) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type returned = Type.getReturnType(method.desc);
        InsnList body = new InsnList();

        body.add(isStatic ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new LdcInsnNode(owner));
        body.add(new LdcInsnNode(method.name));
        body.add(new LdcInsnNode(arguments.length));
        body.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));

        int slot = isStatic ? 0 : 1;

        for (int i = 0; i < arguments.length; i++) {
            body.add(new InsnNode(Opcodes.DUP));
            body.add(new LdcInsnNode(i));
            body.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), slot));
            box(body, arguments[i]);
            body.add(new InsnNode(Opcodes.AASTORE));
            slot += arguments[i].getSize();
        }

        String call = "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)";

        switch (returned.getSort()) {
            case Type.VOID -> {
                body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "callV", call + "V", false));
                body.add(new InsnNode(Opcodes.RETURN));
            }
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                String name = returned.getSort() == Type.BOOLEAN ? "callZ" : "callI";
                String result = returned.getSort() == Type.BOOLEAN ? "Z" : "I";
                body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, name, call + result, false));

                if (returned.getSort() == Type.BYTE) {
                    body.add(new InsnNode(Opcodes.I2B));
                } else if (returned.getSort() == Type.CHAR) {
                    body.add(new InsnNode(Opcodes.I2C));
                } else if (returned.getSort() == Type.SHORT) {
                    body.add(new InsnNode(Opcodes.I2S));
                }

                body.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.LONG -> {
                body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "callJ", call + "J", false));
                body.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "callF", call + "F", false));
                body.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "callD", call + "D", false));
                body.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "call", call + "Ljava/lang/Object;",
                        false));
                body.add(new TypeInsnNode(Opcodes.CHECKCAST, returned.getSort() == Type.ARRAY
                        ? returned.getDescriptor() : returned.getInternalName()));
                body.add(new InsnNode(Opcodes.ARETURN));
            }
        }

        method.instructions = body;
        method.tryCatchBlocks.clear();

        if (method.localVariables != null) {
            method.localVariables.clear();
        }

        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
    }

    private static void box(InsnList body, Type type) {
        String boxed = switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };

        if (boxed != null) {
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, boxed, "valueOf",
                    "(" + type.getDescriptor() + ")L" + boxed + ";", false));
        }
    }
}
