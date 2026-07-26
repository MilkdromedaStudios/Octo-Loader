package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import studios.milkdromeda.octo.compat.api.ApiRule;
import studios.milkdromeda.octo.compat.api.ApiRuleSet;

/**
 * Applies API changes that a rename table cannot express.
 *
 * <p>Remapping handles members that kept their shape. This handles the rest:
 * a method that moved to a different class, an instance method that became
 * static, a field that turned into a getter, and calls into an API that was
 * deleted outright — which become a value of the right type instead of a
 * {@code NoSuchMethodError} at class-init time.
 */
public final class ApiMigrationTransformer implements Transformer {
    private final ApiRuleSet rules;
    private final boolean allowRemoval;

    /**
     * @param allowRemoval whether calls to deleted API may be replaced with a
     *                     default value; off means those calls are left to fail
     */
    public ApiMigrationTransformer(ApiRuleSet rules, boolean allowRemoval) {
        this.rules = rules;
        this.allowRemoval = allowRemoval;
    }

    @Override
    public String name() {
        return "api-migration(" + rules.size() + " rules)";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return !rules.isEmpty();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        Rewriter rewriter = new Rewriter(writer, context);
        reader.accept(rewriter, 0);
        return rewriter.changed ? writer.toByteArray() : bytes;
    }

    private final class Rewriter extends ClassVisitor {
        private final TransformContext context;
        boolean changed;

        Rewriter(ClassVisitor next, TransformContext context) {
            super(Opcodes.ASM9, next);
            this.context = context;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);

            return new MethodVisitor(Opcodes.ASM9, next) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                        boolean isInterface) {
                    ApiRule rule = rules.findMethod(owner, name, descriptor);

                    if (rule == null) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        return;
                    }

                    // A constructor call sits on top of a NEW and a DUP that this
                    // visitor cannot see, so discarding or redirecting it would
                    // leave the stack malformed. Constructors are left alone; if
                    // the class is gone entirely, a stand-in class covers it.
                    if (name.equals("<init>") && rule.kind() != ApiRule.Kind.METHOD_MOVED) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        return;
                    }

                    switch (rule.kind()) {
                        case METHOD_MOVED -> {
                            changed = true;
                            context.note(rule.describe() + (rule.note().isBlank() ? "" : " - " + rule.note()));
                            super.visitMethodInsn(opcode, rule.newOwner(), rule.newName(),
                                    rule.newDescriptor() != null ? rule.newDescriptor() : descriptor, isInterface);
                        }
                        case METHOD_TO_STATIC, REDIRECT_TO_SHIM -> {
                            changed = true;
                            context.note(rule.describe() + " (redirected)");
                            // The receiver is already on the stack below the
                            // arguments, so prefixing it to the descriptor turns
                            // the virtual call into a static one with no shuffling.
                            String target = rule.newDescriptor() != null ? rule.newDescriptor()
                                    : opcode == Opcodes.INVOKESTATIC ? descriptor
                                    : prefixReceiver(owner, descriptor);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, rule.newOwner(), rule.newName(),
                                    target, false);
                        }
                        case REMOVED -> {
                            if (!allowRemoval) {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                return;
                            }

                            changed = true;
                            context.note(rule.describe() + " - call discarded");
                            discardCall(this, opcode, descriptor);
                        }
                        default -> super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    ApiRule rule = rules.findField(owner, name, descriptor);

                    if (rule == null) {
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                        return;
                    }

                    boolean reading = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;

                    if (rule.kind() == ApiRule.Kind.FIELD_TO_METHOD && reading) {
                        changed = true;
                        context.note(rule.describe() + " - field became a method");
                        boolean isStatic = opcode == Opcodes.GETSTATIC;
                        super.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL,
                                rule.newOwner(), rule.newName(),
                                rule.newDescriptor() != null ? rule.newDescriptor() : "()" + descriptor, false);
                        return;
                    }

                    if (rule.kind() == ApiRule.Kind.REMOVED && allowRemoval && reading) {
                        changed = true;
                        context.note(rule.describe() + " - field read replaced with a default");

                        if (opcode == Opcodes.GETFIELD) {
                            super.visitInsn(Opcodes.POP);
                        }

                        pushDefault(this, Type.getType(descriptor));
                        return;
                    }

                    super.visitFieldInsn(opcode, owner, name, descriptor);
                }
            };
        }
    }

    /** {@code (I)V} on {@code com/example/Foo} becomes {@code (Lcom/example/Foo;I)V}. */
    static String prefixReceiver(String owner, String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        Type[] extended = new Type[arguments.length + 1];
        extended[0] = Type.getObjectType(owner);
        System.arraycopy(arguments, 0, extended, 1, arguments.length);
        return Type.getMethodDescriptor(Type.getReturnType(descriptor), extended);
    }

    /** Pops what the call would have consumed and pushes what it would have produced. */
    private static void discardCall(MethodVisitor visitor, int opcode, String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);

        for (int i = arguments.length - 1; i >= 0; i--) {
            visitor.visitInsn(arguments[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        }

        if (opcode != Opcodes.INVOKESTATIC) {
            visitor.visitInsn(Opcodes.POP);
        }

        pushDefault(visitor, Type.getReturnType(descriptor));
    }

    private static void pushDefault(MethodVisitor visitor, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> {
                // Nothing to push.
            }
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> visitor.visitInsn(Opcodes.ICONST_0);
            case Type.LONG -> visitor.visitInsn(Opcodes.LCONST_0);
            case Type.FLOAT -> visitor.visitInsn(Opcodes.FCONST_0);
            case Type.DOUBLE -> visitor.visitInsn(Opcodes.DCONST_0);
            default -> visitor.visitInsn(Opcodes.ACONST_NULL);
        }
    }
}
