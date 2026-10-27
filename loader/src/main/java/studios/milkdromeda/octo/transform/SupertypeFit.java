package studios.milkdromeda.octo.transform;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Fits a class from another version to what its supertypes, and the types it
 * calls, are now: classes or interfaces.
 *
 * <p>The game turns one into the other between releases, and a class written
 * against the old kind does not load:
 * <ul>
 * <li>an interface that became a class can no longer be implemented —
 * {@code RecipeSerializer} is a final record since 26.x, and every recipe
 * serializer Create has implements it. The claim is dropped.</li>
 * <li>a class that became an interface can no longer be extended —
 * {@code Feature} is an interface in 26.x, and Create's
 * {@code LayeredOreFeature} extends it. It is implemented instead, the class
 * extends {@code Object}, and the constructor's call to the old superclass's
 * constructor becomes a call to {@code Object}'s.</li>
 * <li>a call names its owner's kind in the instruction, and the wrong one is
 * an {@code IncompatibleClassChangeError} where it runs. Every call is made
 * with the instruction the owner's kind now takes.</li>
 * </ul>
 * The class loads, and only what relied on the old relationship fails, at
 * that one place.
 */
public final class SupertypeFit implements Transformer {
    private static final OctoLog LOG = OctoLog.of(SupertypeFit.class);
    private static final String OBJECT = "java/lang/Object";

    private final MemberIndex index;
    private final boolean everything;

    /** @param everything whether to fit every class, for the restored ones, rather than translated mods' */
    public SupertypeFit(MemberIndex index, boolean everything) {
        this.index = index;
        this.everything = everything;
    }

    @Override
    public String name() {
        return "supertype-fit";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return everything || context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = fitInterfaces(node);
        String formerSuper = null;

        if (node.superName != null && !node.superName.equals(OBJECT)
                && Boolean.TRUE.equals(index.isInterface(node.superName))) {
            formerSuper = node.superName;
            LOG.info("{} extends {}, which is an interface in this Minecraft; it implements it instead",
                    className.replace('/', '.'), formerSuper.replace('/', '.'));
            node.superName = OBJECT;

            if (!node.interfaces.contains(formerSuper)) {
                node.interfaces.add(formerSuper);
            }

            node.signature = null;
            changed = true;
        }

        for (MethodNode method : node.methods) {
            changed |= fitCalls(method, formerSuper);
        }

        if (!changed) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private boolean fitInterfaces(ClassNode node) {
        List<String> kept = new ArrayList<>(node.interfaces.size());

        for (String iface : node.interfaces) {
            if (Boolean.FALSE.equals(index.isInterface(iface))) {
                LOG.info("{} implements {}, which is a class in this Minecraft; it no longer claims to",
                        node.name.replace('/', '.'), iface.replace('/', '.'));
            } else {
                kept.add(iface);
            }
        }

        if (kept.size() == node.interfaces.size()) {
            return false;
        }

        node.interfaces = kept;
        // The generic signature names the dropped interface too; without it,
        // reflection reads the plain declaration instead.
        node.signature = null;
        return true;
    }

    private boolean fitCalls(MethodNode method, String formerSuper) {
        boolean changed = false;
        boolean constructor = method.name.equals("<init>");
        boolean superCalled = false;

        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }

            if (formerSuper != null && call.owner.equals(formerSuper) && call.getOpcode() == Opcodes.INVOKESPECIAL) {
                if (call.name.equals("<init>") && constructor && !superCalled && !constructs(method, formerSuper)) {
                    // super(...) on what is an interface now: Object's instead.
                    InsnList replacement = new InsnList();
                    Type[] arguments = Type.getArgumentTypes(call.desc);

                    for (int i = arguments.length - 1; i >= 0; i--) {
                        replacement.add(new InsnNode(arguments[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                    }

                    replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, OBJECT, "<init>", "()V", false));
                    method.instructions.insertBefore(call, replacement);
                    method.instructions.remove(call);
                    superCalled = true;
                    changed = true;
                    continue;
                }

                if (!call.name.equals("<init>") && !call.itf) {
                    // super.method(), now a call to the interface's default.
                    call.itf = true;
                    changed = true;
                    continue;
                }
            }

            changed |= fitOpcode(call);
        }

        return changed;
    }

    /** Whether the method builds an object of the type itself, rather than calling its constructor as super. */
    private static boolean constructs(MethodNode method, String type) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.NEW && ((TypeInsnNode) instruction).desc.equals(type)) {
                return true;
            }
        }

        return false;
    }

    /** The instruction a call takes now that its owner is a class, or an interface. */
    private boolean fitOpcode(MethodInsnNode call) {
        if (call.name.startsWith("<") || call.owner.startsWith("[")) {
            return false;
        }

        Boolean isInterface = index.isInterface(call.owner);

        if (isInterface == null) {
            return false;
        }

        if (isInterface) {
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                call.setOpcode(Opcodes.INVOKEINTERFACE);
                call.itf = true;
                return true;
            }

            if ((call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL) && !call.itf) {
                call.itf = true;
                return true;
            }
        } else {
            if (call.getOpcode() == Opcodes.INVOKEINTERFACE) {
                call.setOpcode(Opcodes.INVOKEVIRTUAL);
                call.itf = false;
                return true;
            }

            if ((call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL) && call.itf) {
                call.itf = false;
                return true;
            }
        }

        return false;
    }
}
