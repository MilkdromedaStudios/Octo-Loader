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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Makes a translated class verify against the game it now runs in.
 *
 * <p>The JVM verifies a whole class before running any of it, and checks that
 * every value handed on as a class type really is one. A class from another
 * version can fail that in one method and lose all the others: the hierarchy
 * it was compiled against changed under it. Create's recipe types return a
 * {@code SimpleCraftingRecipeSerializer}, added back from 1.21.1, as a
 * {@code RecipeSerializer}, which is a final record since 26.x and no longer
 * its supertype; that one lambda made all of {@code AllRecipeTypes} unloadable.
 *
 * <p>This runs the verifier's own analysis over the class, against this
 * game's hierarchy, and where a value does not fit the class it is handed to —
 * returned, stored in a field or an array, passed to a method — puts a cast
 * there. The class then verifies; the cast only fails if that path runs, and
 * then it fails there, where the guards can contain it, instead of everywhere.
 */
public final class VerifierFit implements Transformer {
    private static final OctoLog LOG = OctoLog.of(VerifierFit.class);

    private final MemberIndex index;
    private final boolean everything;
    /**
     * The class being fitted on this thread, which the index must not be asked
     * about while it is being read. Fitting one class can read, and so fit,
     * another, so each transform puts back what it found.
     */
    private final ThreadLocal<ClassNode> fitting = new ThreadLocal<>();

    /** @param everything whether to fit every class, for the restored ones, rather than translated mods' */
    public VerifierFit(MemberIndex index, boolean everything) {
        this.index = index;
        this.everything = everything;
    }

    @Override
    public String name() {
        return "verifier-fit";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return everything || context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);

        if ((node.version & 0xFFFF) < Opcodes.V1_7) {
            // Before stack map frames the verifier infers types itself, and
            // what is cast here is what it would have inferred.
            return bytes;
        }

        int casts = 0;
        ClassNode previous = fitting.get();
        fitting.set(node);

        try {
            for (MethodNode method : node.methods) {
                if (method.instructions.size() > 0) {
                    casts += fit(node, method);
                }
            }
        } finally {
            fitting.set(previous);
        }

        if (casts == 0) {
            return bytes;
        }

        LOG.debug("{}: {} value(s) cast to the class this game expects there", className.replace('/', '.'), casts);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private int fit(ClassNode owner, MethodNode method) {
        Frame<BasicValue>[] frames;

        try {
            frames = new Analyzer<>(new Hierarchy(owner)).analyze(owner.name, method);
        } catch (AnalyzerException | RuntimeException e) {
            return 0;
        }

        AbstractInsnNode[] instructions = method.instructions.toArray();
        int casts = 0;

        for (int i = 0; i < instructions.length; i++) {
            Frame<BasicValue> frame = frames[i];
            AbstractInsnNode instruction = instructions[i];

            if (frame == null) {
                continue;
            }

            int top = frame.getStackSize() - 1;

            switch (instruction.getOpcode()) {
                case Opcodes.ARETURN -> casts += castTop(method, instruction, frame, top,
                        Type.getReturnType(method.desc));
                case Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> casts += castTop(method, instruction, frame, top,
                        Type.getType(((FieldInsnNode) instruction).desc));
                case Opcodes.AASTORE -> {
                    Type array = frame.getStack(top - 2).getType();

                    if (array != null && array.getSort() == Type.ARRAY) {
                        casts += castTop(method, instruction, frame, top,
                                Type.getType(array.getDescriptor().substring(1)));
                    }
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE ->
                    casts += castArguments(method, (MethodInsnNode) instruction, frame);
                default -> {
                }
            }
        }

        return casts;
    }

    private int castTop(MethodNode method, AbstractInsnNode at, Frame<BasicValue> frame, int slot, Type wanted) {
        if (slot < 0 || !misfits(frame.getStack(slot), wanted)) {
            return 0;
        }

        method.instructions.insertBefore(at, cast(wanted));
        return 1;
    }

    /**
     * The cast that makes a value fit {@code wanted}. A record that was an
     * interface — {@code RecipeSerializer} — is built from the value instead
     * ({@link studios.milkdromeda.octo.bridge.RecordAdapter}): the value never
     * was one, and a cast would only fail.
     */
    private InsnList cast(Type wanted) {
        InsnList out = new InsnList();
        MemberIndex.Shape shape = index.shape(wanted.getInternalName());

        if (shape != null && "java/lang/Record".equals(shape.superName())) {
            out.add(new LdcInsnNode(wanted));
            out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/bridge/RecordAdapter",
                    "adapt", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false));
        }

        out.add(new TypeInsnNode(Opcodes.CHECKCAST, wanted.getInternalName()));
        return out;
    }

    /**
     * Casts every argument that does not fit. One on top of the stack is cast
     * where it is; one under others has those set aside in fresh locals
     * first, and put back after.
     */
    private int castArguments(MethodNode method, MethodInsnNode call, Frame<BasicValue> frame) {
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int top = frame.getStackSize() - 1;
        int deepest = -1;

        for (int j = 0; j < arguments.length; j++) {
            int slot = top - (arguments.length - 1 - j);

            if (slot >= 0 && misfits(frame.getStack(slot), arguments[j])) {
                deepest = deepest < 0 ? j : deepest;
            }
        }

        if (deepest < 0) {
            return 0;
        }

        InsnList fix = new InsnList();
        List<Integer> saved = new ArrayList<>();
        int local = method.maxLocals;

        // Set aside everything above the deepest misfit, last argument first.
        for (int j = arguments.length - 1; j > deepest; j--) {
            fix.add(new VarInsnNode(arguments[j].getOpcode(Opcodes.ISTORE), local));
            saved.add(0, local);
            local += arguments[j].getSize();
        }

        int casts = 0;

        for (int j = deepest; j < arguments.length; j++) {
            if (j > deepest) {
                fix.add(new VarInsnNode(arguments[j].getOpcode(Opcodes.ILOAD), saved.get(j - deepest - 1)));
            }

            int slot = top - (arguments.length - 1 - j);

            if (misfits(frame.getStack(slot), arguments[j])) {
                fix.add(cast(arguments[j]));
                casts++;
            }
        }

        method.maxLocals = local;
        method.instructions.insertBefore(call, fix);
        return casts;
    }

    /** A class-typed value handed to a class type this game says it is not a subtype of. */
    private boolean misfits(BasicValue value, Type wanted) {
        Type actual = value == null ? null : value.getType();

        if (actual == null || wanted == null || actual.getSort() != Type.OBJECT || wanted.getSort() != Type.OBJECT
                || actual.equals(wanted) || actual.getInternalName().equals("null")
                || wanted.getInternalName().equals("java/lang/Object")) {
            return false;
        }

        // The verifier lets anything through to an interface, and says nothing
        // about a class it cannot see; nor does this.
        if (!Boolean.FALSE.equals(isInterface(wanted.getInternalName()))) {
            return false;
        }

        return Boolean.FALSE.equals(isSubtype(actual.getInternalName(), wanted.getInternalName()));
    }

    private Boolean isInterface(String name) {
        ClassNode owner = fitting.get();
        if (owner != null && name.equals(owner.name)) {
            return (owner.access & Opcodes.ACC_INTERFACE) != 0;
        }

        return index.isInterface(name);
    }

    /** @return {@code null} when the type cannot be seen */
    private Boolean isSubtype(String type, String ancestor) {
        if (type.equals(ancestor)) {
            return true;
        }

        ClassNode owner = fitting.get();

        if (owner != null && type.equals(owner.name)) {
            if (owner.superName != null && Boolean.TRUE.equals(isSubtype(owner.superName, ancestor))) {
                return true;
            }

            for (String iface : owner.interfaces) {
                if (Boolean.TRUE.equals(isSubtype(iface, ancestor))) {
                    return true;
                }
            }

            return false;
        }

        if (index.shape(type) == null) {
            return null;
        }

        return index.isSubtype(type, ancestor);
    }

    /** The verifier's analysis, over this game's classes as the index reads them. */
    private final class Hierarchy extends SimpleVerifier {
        Hierarchy(ClassNode owner) {
            super(Opcodes.ASM9, Type.getObjectType(owner.name),
                    owner.superName == null ? null : Type.getObjectType(owner.superName),
                    owner.interfaces.stream().map(Type::getObjectType).toList(),
                    (owner.access & Opcodes.ACC_INTERFACE) != 0);
        }

        @Override
        protected boolean isInterface(Type type) {
            return Boolean.TRUE.equals(VerifierFit.this.isInterface(type.getInternalName()));
        }

        @Override
        protected Type getSuperClass(Type type) {
            ClassNode owner = fitting.get();

            if (owner != null && type.getInternalName().equals(owner.name)) {
                return owner.superName == null ? null : Type.getObjectType(owner.superName);
            }

            MemberIndex.Shape shape = index.shape(type.getInternalName());
            return shape == null || shape.superName() == null ? null : Type.getObjectType(shape.superName());
        }

        @Override
        protected boolean isAssignableFrom(Type type, Type other) {
            if (type.equals(other)) {
                return true;
            }

            // Arrays and anything unseen pass, as far as finding misfits goes.
            if (type.getSort() != Type.OBJECT || other.getSort() != Type.OBJECT || isInterface(type)) {
                return true;
            }

            return !Boolean.FALSE.equals(isSubtype(other.getInternalName(), type.getInternalName()));
        }

        /**
         * Every value is taken where it is handed on: a misfit is exactly what
         * this is looking for, so finding one must not end the analysis.
         */
        @Override
        protected boolean isSubTypeOf(BasicValue value, BasicValue expected) {
            return true;
        }

        @Override
        protected Class<?> getClass(Type type) {
            throw new UnsupportedOperationException("the game's classes are read, never loaded, here");
        }
    }
}
