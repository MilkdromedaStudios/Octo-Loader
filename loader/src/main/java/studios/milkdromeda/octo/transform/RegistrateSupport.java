package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Registrate, the registration library under Create and most of its addons,
 * made to register what it can on a version it was not built for.
 *
 * <p>Two things stood in the way. Registrate builds each entry itself and
 * stops at the first that fails — {@code skipErrors} would let it go on, but
 * it refuses that outside a development environment — so one block this game
 * could not make cost every block after it. And it builds each entry outside
 * NeoForge's own registration call, where the id a block now has to carry
 * is known. So {@code skipErrors} is always on, and each entry is built with
 * its registry and name recorded for {@link studios.milkdromeda.octo.bridge.RegistrationContext}.
 */
public final class RegistrateSupport implements Transformer {
    private static final String REGISTRATE = "com/tterrag/registrate/AbstractRegistrate";
    private static final String REGISTRATION = REGISTRATE + "$Registration";
    private static final String CONTEXT = "studios/milkdromeda/octo/bridge/RegistrationContext";

    @Override
    public String name() {
        return "registrate";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(REGISTRATE) || className.equals(REGISTRATION);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = className.equals(REGISTRATE) ? skipErrors(node) : recordEntries(node);

        if (!changed) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code this.skipErrors = true} at the end of every constructor. */
    private static boolean skipErrors(ClassNode node) {
        if (node.fields.stream().noneMatch(field -> field.name.equals("skipErrors") && field.desc.equals("Z"))) {
            return false;
        }

        boolean changed = false;

        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }

            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.RETURN) {
                    InsnList set = new InsnList();
                    set.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    set.add(new InsnNode(Opcodes.ICONST_1));
                    set.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "skipErrors", "Z"));
                    method.instructions.insertBefore(instruction, set);
                    changed = true;
                }
            }
        }

        return changed;
    }

    /** {@code register(event)} enters the registration context with its type and name, and leaves it. */
    private static boolean recordEntries(ClassNode node) {
        FieldNode type = field(node, "type");
        FieldNode name = field(node, "name");

        if (type == null || name == null) {
            return false;
        }

        for (MethodNode method : node.methods) {
            if (!method.name.equals("register") || !method.desc.endsWith(")V")) {
                continue;
            }

            InsnList enter = new InsnList();
            enter.add(new VarInsnNode(Opcodes.ALOAD, 0));
            enter.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, type.name, type.desc));
            enter.add(new VarInsnNode(Opcodes.ALOAD, 0));
            enter.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, name.name, name.desc));
            enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "enter",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            method.instructions.insert(enter);

            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.RETURN) {
                    method.instructions.insertBefore(instruction,
                            new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "exit", "()V", false));
                }
            }

            // Registrate logs only that an entry failed, never why: the cause
            // is said here, with the entry's id, before it goes on its way.
            org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(method.desc);

            if ((method.access & Opcodes.ACC_STATIC) == 0 && arguments.length == 1
                    && arguments[0].getSort() == org.objectweb.asm.Type.OBJECT) {
                LabelNode start = new LabelNode();
                LabelNode end = new LabelNode();
                LabelNode handler = new LabelNode();
                method.instructions.insert(start);
                method.instructions.add(end);
                method.instructions.add(handler);
                method.instructions.add(new FrameNode(Opcodes.F_FULL, 2,
                        new Object[] { node.name, arguments[0].getInternalName() }, 1,
                        new Object[] { "java/lang/Throwable" }));
                method.instructions.add(new InsnNode(Opcodes.DUP));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "failed",
                        "(Ljava/lang/Throwable;)V", false));
                method.instructions.add(new InsnNode(Opcodes.ATHROW));
                method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
            }

            return true;
        }

        return false;
    }

    private static FieldNode field(ClassNode node, String name) {
        return node.fields.stream().filter(field -> field.name.equals(name)).findFirst().orElse(null);
    }
}
