package studios.milkdromeda.octo.transform;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Keeps one failing callback in a mod from another version from taking the
 * game down with it.
 *
 * <p>A mod's work after start-up happens in the callbacks it hands the game and
 * the loader APIs: an event listener, a tick hook, a command. Those run inside
 * the game's own loop, so whatever escapes one escapes into the server tick and
 * ends the server — Jade's {@code SERVER_STARTED} listener reaching a class
 * that would not initialise was a crash report, not one feature missing.
 * Translation makes that kind of failure expected rather than exceptional: a
 * default where a member was bypassed is null one call later.
 *
 * <p>So the lambda bodies of translated mods — which is what nearly every
 * callback is compiled to — are wrapped: what a translation can cause is
 * logged once and the callback returns a default; a checked exception the
 * callback declares, and anything the JVM itself is failing with, goes on as
 * written ({@link GapBridge#contain}). {@code -Docto.strict=true} turns this
 * off.
 *
 * <p>The same goes for what their mixins add to the game's own methods. Xaero's
 * Minimap hooks the start of every client tick; with its initialiser failed,
 * that hook threw on the first tick and the crash report named
 * {@code Minecraft.tick}. A handler is wrapped only where giving up has an
 * answer that leaves the game as it would be without the mod: an
 * {@code @Inject} just returns, a {@code @Modify...} hands back the value it
 * was given, and a {@code @WrapWithCondition} lets the call it guards go
 * ahead. A {@code @Redirect} or {@code @WrapOperation} replaces the game's own
 * work and has no such answer, so it is left as written.
 */
public final class CallbackGuard implements Transformer {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    /** What a handler gives back when it fails. */
    enum Fallback {
        /** Returns the default for its type, which for a void handler is just returning. */
        DEFAULT,
        /** Returns its first argument: the value it was asked to modify. */
        FIRST_ARGUMENT,
        /** Returns true: the guarded call goes ahead. */
        TRUE,
        /**
         * Returns the last argument of its own result type: a lambda like
         * {@code builder -> builder.persistent(codec)} hands the builder on
         * unchanged rather than a null the caller then calls a method on.
         */
        SAME_TYPE_ARGUMENT
    }

    private static final Set<String> RETURNING_INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;");

    private static final Set<String> MODIFIERS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;");

    private static final Set<String> CONDITIONS = Set.of(
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;");
    @Override
    public String name() {
        return "callback-guard";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        Map<String, Fallback> handlers = handlers(reader);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = { false };
        boolean[] isInterface = { false };
        int[] version = { 0 };

        Map<Handle, Handle> bridges = new LinkedHashMap<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int classVersion, int access, String name, String signature, String superName,
                    String[] interfaces) {
                version[0] = classVersion & 0xFFFF;
                isInterface[0] = (access & Opcodes.ACC_INTERFACE) != 0;
                super.visit(classVersion, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (delegate == null || version[0] < Opcodes.V1_8) {
                    return delegate;
                }

                // Interfaces may hold the private bridges only from Java 9 on.
                if (!isInterface[0] || version[0] >= Opcodes.V9) {
                    delegate = new BridgingReferences(delegate, className, isInterface[0], bridges);
                }

                Fallback fallback = isCallback(access, name)
                        ? sameTypeSlot(descriptor, (access & Opcodes.ACC_STATIC) != 0) >= 0
                                ? Fallback.SAME_TYPE_ARGUMENT : Fallback.DEFAULT
                        : handlers.get(name + descriptor);

                if (fallback == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return delegate;
                }

                changed[0] = true;
                return new Guarded(delegate, descriptor, (access & Opcodes.ACC_STATIC) != 0, fallback,
                        className.replace('/', '.') + "." + name);
            }

            @Override
            public void visitEnd() {
                bridges.forEach((target, bridge) -> writeBridge(cv, target, bridge));
                super.visitEnd();
            }
        }, 0);

        return changed[0] || !bridges.isEmpty() ? writer.toByteArray() : bytes;
    }

    /**
     * Points a method reference at a guarded bridge to its target.
     *
     * <p>{@code ClientTickEvents.END_CLIENT_TICK.register(ClientProxy::onKeyPressed)}
     * is a callback like any lambda, but compiles to a reference to an
     * ordinary method, which the rest of the mod may call directly and expect
     * to throw. So the method is left as it is, and only the reference goes
     * through a bridge that calls it with the guard around it: Jade's tick
     * listener, with its key bindings never made, failed this way on the first
     * tick and ended the game.
     */
    private static final class BridgingReferences extends MethodVisitor {
        private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";

        private final String owner;
        private final boolean ownerIsInterface;
        private final Map<Handle, Handle> bridges;

        BridgingReferences(MethodVisitor delegate, String owner, boolean ownerIsInterface,
                Map<Handle, Handle> bridges) {
            super(Opcodes.ASM9, delegate);
            this.owner = owner;
            this.ownerIsInterface = ownerIsInterface;
            this.bridges = bridges;
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
            if (bootstrap.getOwner().equals(METAFACTORY) && bootstrap.getName().equals("metafactory")
                    && arguments.length == 3 && arguments[1] instanceof Handle target && isBridgeable(target)) {
                // A bridge in an interface is an interface method, and its handle has to say so.
                Handle bridge = bridges.computeIfAbsent(target, ignored -> new Handle(Opcodes.H_INVOKESTATIC, owner,
                        "lambda$octo$reference$" + bridges.size(), bridgeDescriptor(target), ownerIsInterface));
                arguments = new Object[] { arguments[0], bridge, arguments[2] };
            }

            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
        }

        /**
         * A method of this class's own: a reference to a JDK method such as
         * {@code Integer::parseInt} throws what its callers are written to
         * catch, and one to another class's protected method cannot be made
         * from here at all.
         */
        private boolean isBridgeable(Handle target) {
            if (!target.getOwner().equals(owner) || target.getName().startsWith("lambda$")
                    || target.getName().startsWith("<")) {
                return false;
            }

            return switch (target.getTag()) {
                case Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE,
                        Opcodes.H_INVOKESPECIAL -> true;
                default -> false;
            };
        }
    }

    /** The target's own parameters, with its receiver first unless it is static. */
    private static String bridgeDescriptor(Handle target) {
        if (target.getTag() == Opcodes.H_INVOKESTATIC) {
            return target.getDesc();
        }

        return "(" + Type.getObjectType(target.getOwner()).getDescriptor() + target.getDesc().substring(1);
    }

    private static void writeBridge(ClassVisitor writer, Handle target, Handle bridge) {
        MethodVisitor method = new Guarded(writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, bridge.getName(), bridge.getDesc(),
                null, null), bridge.getDesc(), true, Fallback.DEFAULT,
                target.getOwner().replace('/', '.') + "." + target.getName());
        method.visitCode();
        int slot = 0;

        for (Type argument : Type.getArgumentTypes(bridge.getDesc())) {
            method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
            slot += argument.getSize();
        }

        int opcode = switch (target.getTag()) {
            case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
            case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
            case Opcodes.H_INVOKESPECIAL -> Opcodes.INVOKESPECIAL;
            default -> Opcodes.INVOKEVIRTUAL;
        };
        method.visitMethodInsn(opcode, target.getOwner(), target.getName(), target.getDesc(), target.isInterface());
        Type returned = Type.getReturnType(target.getDesc());
        method.visitInsn(returned.getOpcode(Opcodes.IRETURN));
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    /**
     * Wraps one method of a class in the guard: when it fails, it is reported
     * once and returns its type's default.
     */
    public static byte[] guardMethod(byte[] bytes, String className, String name, String descriptor) {
        return guardMethods(bytes, className, Set.of(name + descriptor));
    }

    /**
     * Guards each of the class's methods named, by name and descriptor, the
     * way a callback is: what it throws is contained, and it returns its
     * type's default.
     */
    public static byte[] guardMethods(byte[] bytes, String className, Set<String> methods) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String desc, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, method, desc, signature, exceptions);

                if (delegate == null || !methods.contains(method + desc)
                        || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return delegate;
                }

                changed[0] = true;
                return new Guarded(delegate, desc, (access & Opcodes.ACC_STATIC) != 0, Fallback.DEFAULT,
                        className.replace('/', '.') + "." + method);
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }

    /**
     * The mixin handlers in a class, by name and descriptor, each with what it
     * gives back when it fails; empty for anything that is not a mixin.
     */
    static Map<String, Fallback> handlers(ClassReader reader) {
        Map<String, Fallback> out = new HashMap<>();
        boolean[] isMixin = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                isMixin[0] |= MIXIN.equals(descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        Fallback fallback = fallbackFor(annotation, descriptor);

                        if (fallback != null) {
                            out.put(name + descriptor, fallback);
                        }

                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return isMixin[0] ? out : Map.of();
    }

    private static Fallback fallbackFor(String annotation, String descriptor) {
        Type returned = Type.getReturnType(descriptor);
        Type[] arguments = Type.getArgumentTypes(descriptor);

        if (RETURNING_INJECTORS.contains(annotation) && returned.getSort() == Type.VOID) {
            return Fallback.DEFAULT;
        }

        if (MODIFIERS.contains(annotation) && arguments.length > 0 && arguments[0].equals(returned)) {
            return Fallback.FIRST_ARGUMENT;
        }

        if (CONDITIONS.contains(annotation) && returned.getSort() == Type.BOOLEAN) {
            return Fallback.TRUE;
        }

        return null;
    }

    /** The local slot of the last parameter of the method's own result type, or -1. */
    static int sameTypeSlot(String descriptor, boolean isStatic) {
        Type returned = Type.getReturnType(descriptor);

        if (returned.getSort() == Type.VOID) {
            return -1;
        }

        int slot = isStatic ? 0 : 1;
        int found = -1;

        for (Type argument : Type.getArgumentTypes(descriptor)) {
            if (argument.equals(returned)) {
                found = slot;
            }

            slot += argument.getSize();
        }

        return found;
    }

    /** A lambda body: synthetic, named by javac, with code. */
    static boolean isCallback(int access, String name) {
        return name.startsWith("lambda$") && (access & Opcodes.ACC_SYNTHETIC) != 0
                && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    /** Wraps the whole body in a handler that asks {@link GapBridge} whether to stop there. */
    private static final class Guarded extends MethodVisitor {
        private final Type returnType;
        private final boolean isStatic;
        private final Fallback fallback;
        private final String site;
        /** The argument handed back, or -1. */
        private final int returnSlot;
        private final Label start = new Label();
        private final Label end = new Label();
        private final Label handler = new Label();

        Guarded(MethodVisitor delegate, String descriptor, boolean isStatic, Fallback fallback, String site) {
            super(Opcodes.ASM9, delegate);
            this.returnType = Type.getReturnType(descriptor);
            this.isStatic = isStatic;
            this.fallback = fallback;
            this.site = site;
            this.returnSlot = switch (fallback) {
                case FIRST_ARGUMENT -> isStatic ? 0 : 1;
                case SAME_TYPE_ARGUMENT -> sameTypeSlot(descriptor, isStatic);
                default -> -1;
            };
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitLabel(start);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // Registered last, so every handler the method already had is asked
            // first: only what would have left the method reaches this one.
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(end);
            super.visitLabel(handler);
            // Only the argument handed back is declared, if any: javac never
            // stores anything else in a parameter's slot, so that much holds
            // at every instruction of the body, and the rest is left unsaid.
            Object[] locals = new Object[Math.max(returnSlot + 1, 0)];

            for (int i = 0; i < returnSlot; i++) {
                locals[i] = Opcodes.TOP;
            }

            if (returnSlot >= 0) {
                locals[returnSlot] = frameType(returnType);
            }

            super.visitFrame(Opcodes.F_FULL, locals.length, locals, 1, new Object[] { "java/lang/Throwable" });
            super.visitLdcInsn(site);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/transform/GapBridge", "contain",
                    "(Ljava/lang/Throwable;Ljava/lang/String;)V", false);

            if (returnSlot >= 0) {
                super.visitVarInsn(returnType.getOpcode(Opcodes.ILOAD), returnSlot);
            } else if (fallback == Fallback.TRUE) {
                super.visitInsn(Opcodes.ICONST_1);
            } else {
                Nothing.push(mv, returnType);
            }

            super.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
            super.visitMaxs(maxStack, maxLocals);
        }

        private static Object frameType(Type type) {
            return switch (type.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
                case Type.FLOAT -> Opcodes.FLOAT;
                case Type.LONG -> Opcodes.LONG;
                case Type.DOUBLE -> Opcodes.DOUBLE;
                case Type.ARRAY -> type.getDescriptor();
                default -> type.getInternalName();
            };
        }
    }
}
