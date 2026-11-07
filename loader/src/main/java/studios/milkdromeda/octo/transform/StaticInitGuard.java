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
 *
 * <p>A client class's statement may fail only because the game is not there
 * yet: mods are constructed before Minecraft is, where NeoForge constructs
 * them inside it, and Create's {@code CreateClient} builds its schematic
 * handler from {@code Minecraft.getInstance()}. Left null, every client tick
 * of Create failed for the whole session. So in a class that names the
 * client, each simple statement — straight-line code, using no locals — is
 * also copied into a method of its own, and one that fails is run again from
 * there once the game has started ({@link GapBridge#retryDeferred}); its field
 * is no longer final, so it can be set then.
 */
public final class StaticInitGuard implements Transformer {
    private static final String CONTAIN = "studios/milkdromeda/octo/transform/GapBridge";
    private static final ByteScan NAMES_CLIENT = new ByteScan(java.util.List.of("net/minecraft/client/"));

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

        boolean client = NAMES_CLIENT.matches(bytes);

        for (MethodNode method : new ArrayList<>(node.methods)) {
            if (method.name.equals("<clinit>") && guard(node, method, client)) {
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                return writer.toByteArray();
            }
        }

        return bytes;
    }

    private static boolean guard(ClassNode owner, MethodNode method, boolean client) {
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
            String retry = client ? copy(owner, method, starts.get(i), store, i) : null;

            if (retry != null) {
                handlers.add(new LdcInsnNode(Type.getObjectType(owner.name)));
                handlers.add(new LdcInsnNode(retry));
                handlers.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTAIN, "containLater",
                        "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;)V", false));
            } else {
                handlers.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTAIN, "contain",
                        "(Ljava/lang/Throwable;Ljava/lang/String;)V", false));
            }

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

    /**
     * The statement from {@code start} to its {@code store}, copied into a
     * method of its own that can run it again, when it is simple enough to
     * move: straight-line, with no locals and nothing of the mod's own
     * catching it. Its field stops being final, so that method may set it.
     *
     * @return the method's name, or {@code null} when it is not copied
     */
    private static String copy(ClassNode owner, MethodNode method, AbstractInsnNode start, FieldInsnNode store,
            int index) {
        for (org.objectweb.asm.tree.TryCatchBlockNode block : method.tryCatchBlocks) {
            int from = method.instructions.indexOf(block.start);
            int to = method.instructions.indexOf(block.end);

            if (from <= method.instructions.indexOf(store) && to >= method.instructions.indexOf(start)) {
                return null;
            }
        }

        InsnList body = new InsnList();

        for (AbstractInsnNode node = start; node != null; node = node.getNext()) {
            if (node instanceof org.objectweb.asm.tree.VarInsnNode || node instanceof org.objectweb.asm.tree.IincInsnNode
                    || node instanceof JumpInsnNode || node instanceof org.objectweb.asm.tree.TableSwitchInsnNode
                    || node instanceof org.objectweb.asm.tree.LookupSwitchInsnNode || node instanceof FrameNode
                    || node.getOpcode() == Opcodes.RET) {
                return null;
            }

            // Labels and line numbers stay behind: nothing here jumps.
            if (node.getOpcode() >= 0) {
                body.add(node.clone(java.util.Map.of()));
            }

            if (node == store) {
                break;
            }
        }

        body.add(new InsnNode(Opcodes.RETURN));
        String name = "octo$retry$" + index;
        MethodNode retry = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name,
                "()V", null, null);
        retry.instructions = body;
        owner.methods.add(retry);

        for (org.objectweb.asm.tree.FieldNode field : owner.fields) {
            if (field.name.equals(store.name) && field.desc.equals(store.desc)) {
                field.access &= ~Opcodes.ACC_FINAL;
            }
        }

        return name;
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
