package studios.milkdromeda.octo.transform;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/**
 * Keeps one failing field of a translated class's static initialiser from
 * failing the class.
 *
 * <p>Mods register their content from static initialisers, one field at a
 * time: {@code AllBlocks}, {@code AllItems}, {@code AllDataComponents}. An
 * exception anywhere in one ends the initialiser, the class can never be used,
 * and in Create's case the mod does not construct. One data component whose
 * codec this version could not build took every one of them down.
 *
 * <p>So the initialiser is cut at its statements — each store to a static
 * field of the class, where nothing is left on the stack and no local is
 * still needed — and each statement is guarded on its own. One that fails is
 * reported once and leaves its field at the default; the next one runs.
 */
public final class StaticInitGuard implements Transformer {
    private static final String CONTAIN = "studios/milkdromeda/octo/transform/GapBridge";

    @Override
    public String name() {
        return "static-init-guard";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassNode node = new ClassNode();
        // Expanded, so the frames added here sit among frames of the same kind;
        // the writer compresses them all again.
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);

        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || (node.version & 0xFFFF) < Opcodes.V1_7) {
            return bytes;
        }

        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && guard(node, method)) {
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                return writer.toByteArray();
            }
        }

        return bytes;
    }

    private static boolean guard(ClassNode owner, MethodNode method) {
        Frame<BasicValue>[] frames;

        try {
            frames = new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
        } catch (AnalyzerException | RuntimeException e) {
            return false;
        }

        AbstractInsnNode[] instructions = method.instructions.toArray();
        List<FieldInsnNode> stores = new ArrayList<>();
        List<AbstractInsnNode> starts = new ArrayList<>();
        AbstractInsnNode start = instructions.length == 0 ? null : instructions[0];

        for (int i = 0; i + 1 < instructions.length; i++) {
            if (instructions[i].getOpcode() == Opcodes.PUTSTATIC
                    && ((FieldInsnNode) instructions[i]).owner.equals(owner.name)
                    && isClear(frames[i + 1]) && start != null && !startsAFrame(instructions[i + 1])) {
                stores.add((FieldInsnNode) instructions[i]);
                starts.add(start);
                start = instructions[i + 1];
            } else if (instructions[i].getOpcode() == Opcodes.PUTSTATIC && isClear(frames[i + 1])) {
                start = instructions[i + 1];
            }
        }

        // One statement is nothing to cut; it fails or not as it is.
        if (stores.size() < 2) {
            return false;
        }

        String site = owner.name.replace('/', '.') + ".<clinit>";
        InsnList handlers = new InsnList();
        List<TryCatchBlockNode> blocks = new ArrayList<>();

        for (int i = 0; i < stores.size(); i++) {
            FieldInsnNode store = stores.get(i);
            LabelNode from = new LabelNode();
            LabelNode to = new LabelNode();
            LabelNode handler = new LabelNode();

            method.instructions.insertBefore(starts.get(i), from);
            method.instructions.insert(store, to);
            // After the store, two paths meet: the statement's and its handler's.
            method.instructions.insert(to, new FrameNode(Opcodes.F_NEW, 0, new Object[0], 0, new Object[0]));

            handlers.add(handler);
            handlers.add(new FrameNode(Opcodes.F_NEW, 0, new Object[0], 1, new Object[] { "java/lang/Throwable" }));
            handlers.add(new LdcInsnNode(site + " " + store.name));
            handlers.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTAIN, "contain",
                    "(Ljava/lang/Throwable;Ljava/lang/String;)V", false));
            pushDefault(handlers, Type.getType(store.desc));
            handlers.add(new FieldInsnNode(Opcodes.PUTSTATIC, store.owner, store.name, store.desc));
            handlers.add(new JumpInsnNode(Opcodes.GOTO, to));
            blocks.add(new TryCatchBlockNode(from, to, handler, "java/lang/Throwable"));
        }

        method.instructions.add(handlers);
        // After the mod's own handlers, so only what would have left the
        // initialiser reaches these.
        method.tryCatchBlocks.addAll(blocks);
        return true;
    }

    /** Nothing on the stack, and no local that anything after here still reads. */
    private static boolean isClear(Frame<BasicValue> frame) {
        if (frame == null || frame.getStackSize() != 0) {
            return false;
        }

        for (int i = 0; i < frame.getLocals(); i++) {
            if (frame.getLocal(i) != BasicValue.UNINITIALIZED_VALUE) {
                return false;
            }
        }

        return true;
    }

    /** Whether the code there already carries a frame, which a second one may not join. */
    private static boolean startsAFrame(AbstractInsnNode next) {
        for (AbstractInsnNode node = next; node != null && node.getOpcode() < 0; node = node.getNext()) {
            if (node instanceof FrameNode) {
                return true;
            }
        }

        return false;
    }

    private static void pushDefault(InsnList out, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> out.add(new InsnNode(Opcodes.ICONST_0));
            case Type.LONG -> out.add(new InsnNode(Opcodes.LCONST_0));
            case Type.FLOAT -> out.add(new InsnNode(Opcodes.FCONST_0));
            case Type.DOUBLE -> out.add(new InsnNode(Opcodes.DCONST_0));
            default -> out.add(new InsnNode(Opcodes.ACONST_NULL));
        }
    }
}
