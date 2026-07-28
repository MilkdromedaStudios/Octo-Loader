package studios.milkdromeda.octo.transform;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Manufactures the classes an old mod expects to find and the runtime no longer has.
 *
 * <p>When a mod written for 1.7.10 references a class that was deleted in 1.13,
 * every class that mentions it fails to link — even the parts of the mod that
 * would have worked. Octo reads what the mod actually needs from that class
 * (which methods, with which descriptors, used how) and generates exactly that
 * shape at load time. Calls into it return zero or null instead of bringing the
 * mod down at class-load time; if the mod never reaches that code path, nothing
 * happens at all.
 *
 * <p>This is a last resort, applied after mapping and API rules have had their
 * turn, and every generated class is listed in the mod's compatibility report.
 */
public final class PhantomClasses {
    private static final OctoLog LOG = OctoLog.of(PhantomClasses.class);

    // Concurrent because stand-ins are now also declared on demand, from inside
    // a class load, and the loader is parallel-capable. Planning still happens
    // once up front on one thread; this is for what arrives after it.
    private final Map<String, Spec> specs = new java.util.concurrent.ConcurrentHashMap<>();

    /** What the compatibility table says may be fabricated, and as what. */
    private volatile studios.milkdromeda.octo.compat.api.Equivalents equivalents =
            studios.milkdromeda.octo.compat.api.Equivalents.EMPTY;

    public void useEquivalents(studios.milkdromeda.octo.compat.api.Equivalents table) {
        this.equivalents = table;
    }

    /** What a mod expects a class it references to look like. */
    public static final class Spec {
        private final String internalName;
        private final Set<Member> methods = new LinkedHashSet<>();
        private final Set<Member> fields = new LinkedHashSet<>();
        private boolean isInterface;
        private boolean instantiated;
        private boolean extended;

        Spec(String internalName) {
            this.internalName = internalName;
        }

        public String internalName() {
            return internalName;
        }

        public boolean isInterface() {
            return isInterface && !instantiated && !extended;
        }

        public Set<Member> methods() {
            return methods;
        }

        public Set<Member> fields() {
            return fields;
        }

        @Override
        public String toString() {
            return internalName + " (" + methods.size() + " methods, " + fields.size() + " fields)";
        }
    }

    /** @param isStatic whether the mod used it without a receiver */
    public record Member(String name, String descriptor, boolean isStatic) {
    }

    /**
     * Records everything {@code bytes} needs from classes that {@code exists}
     * says are absent.
     */
    public void observe(byte[] bytes, Predicate<String> exists) {
        observe(bytes, exists, name -> true);
    }

    /**
     * Records everything {@code bytes} needs from absent classes that Octo is
     * willing to answer for.
     *
     * <p>The second predicate is what makes this safe to run over a mod built
     * for the version that is running. For a mod from an older era, every
     * missing reference is fair game — the whole point of the time capsule is
     * that the game moved out from under it. For a current mod, a missing
     * reference is usually a real bug in the mod or a library that failed to
     * load, and manufacturing a silent stand-in for it would turn a clear
     * failure into a mod that runs and quietly does nothing. So a current mod is
     * only answered for the packages Octo is responsible for: the four loaders'
     * APIs, and whatever the compatibility table explicitly declares.
     *
     * @param answerable whether a missing class is Octo's to stand in for
     */
    public void observe(byte[] bytes, Predicate<String> exists, Predicate<String> answerable) {
        Predicate<String> missing = name -> isMissing(name, exists) && answerable.test(name);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
                if (superName != null && missing.test(superName)) {
                    specFor(superName).extended = true;
                }

                if (interfaces != null) {
                    for (String candidate : interfaces) {
                        if (missing.test(candidate)) {
                            specFor(candidate).isInterface = true;
                        }
                    }
                }
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface) {
                        if (!missing.test(owner)) {
                            return;
                        }

                        Spec spec = specFor(owner);

                        if (isInterface || opcode == Opcodes.INVOKEINTERFACE) {
                            spec.isInterface = true;
                        }

                        if (name.equals("<init>")) {
                            spec.instantiated = true;
                        }

                        spec.methods.add(new Member(name, descriptor, opcode == Opcodes.INVOKESTATIC));
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (!missing.test(owner)) {
                            return;
                        }

                        specFor(owner).fields.add(new Member(name, descriptor,
                                opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC));
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (missing.test(type)) {
                            Spec spec = specFor(type);

                            if (opcode == Opcodes.NEW) {
                                spec.instantiated = true;
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
    }

    private static boolean isMissing(String internalName, Predicate<String> exists) {
        if (internalName == null || internalName.isEmpty() || internalName.charAt(0) == '[') {
            return false;
        }

        // Never fabricate anything from the JDK or from Octo itself: an absent
        // java.* class means the JVM is wrong, not the mod.
        if (internalName.startsWith("java/") || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/") || internalName.startsWith("sun/")
                || internalName.startsWith("studios/milkdromeda/octo/")) {
            return false;
        }

        return !exists.test(internalName);
    }

    private Spec specFor(String internalName) {
        return specs.computeIfAbsent(internalName, Spec::new);
    }

    public Map<String, Spec> specs() {
        return specs;
    }

    public boolean has(String internalName) {
        return specs.containsKey(internalName);
    }

    public Spec spec(String internalName) {
        return specs.get(internalName);
    }

    /**
     * Builds the class file for one of the recorded specifications.
     *
     * <p>Prefer this over {@link #generate(Spec)}: knowing the whole set lets a
     * factory method that returns another missing class return an instance of
     * its stand-in rather than null, so the chained calls old code is full of —
     * {@code Foo.create().configure().build()} — keep working instead of
     * failing on the second link.
     */
    public byte[] generate(String internalName) {
        Spec spec = specs.get(internalName);

        if (spec == null) {
            throw new IllegalArgumentException("no stand-in was planned for " + internalName);
        }

        return generate(spec, specs.keySet());
    }

    /**
     * Records a stand-in for a loader interface nobody planned for.
     *
     * <p>Planned stand-ins come from reading an old mod's bytecode, which tells
     * us exactly what shape the missing class has to be. That scan only runs for
     * mods older than the runtime, so a current mod naming a corner of an API
     * Octo does not implement — Flywheel asking a {@code ModContainer} for its
     * {@code IModInfo} — falls straight through to a {@code NoClassDefFoundError}
     * and the mod does not load.
     *
     * <p>These are declared empty interfaces, which is what almost everything in
     * those packages is, and they are only ever reached through a call that has
     * already been rewritten to return null. The value is never used; it just has
     * to have a type.
     */
    public Spec declareInterface(String internalName) {
        Spec spec = specFor(internalName);
        spec.isInterface = true;
        return spec;
    }

    /**
     * Records a stand-in that mods construct or extend rather than implement.
     *
     * <p>The difference is not cosmetic. An empty interface is the right shape
     * for most of what a loader API contains, and the wrong shape for anything a
     * mod says {@code new} about: {@code new ItemStackHandler(9)} against an
     * interface is an {@code InstantiationError}, and extending one is an
     * {@code IncompatibleClassChangeError}. Both are worse than the
     * {@code NoClassDefFoundError} they replaced, because they arrive later.
     */
    public Spec declareClass(String internalName) {
        Spec spec = specFor(internalName);
        spec.isInterface = false;
        spec.instantiated = true;
        return spec;
    }

    /**
     * Declares whatever the compatibility table says this class should be.
     *
     * <p>This is the only route by which a {@code net.minecraft.*} class is ever
     * fabricated. Octo will invent a missing corner of a loader API on its own
     * initiative, because that gap is Octo's own; inventing a piece of Minecraft
     * is a judgement about the game, so it has to be one somebody wrote down in
     * the table first.
     *
     * @return the recorded specification, or {@code null} when the table has
     *         nothing to say about this class or says not to stand in for it
     */
    public Spec declareFromTable(String internalName) {
        studios.milkdromeda.octo.compat.api.Equivalents.ClassEntry entry =
                equivalents.classEntry(internalName);

        if (entry == null) {
            return null;
        }

        return switch (entry.stub()) {
            case INTERFACE -> declareInterface(internalName);
            case CLASS -> declareClass(internalName);
            case NONE -> null;
        };
    }

    /** Builds the class file for a recorded specification. */
    public static byte[] generate(Spec spec) {
        return generate(spec, java.util.Set.of(spec.internalName()));
    }

    private static byte[] generate(Spec spec, java.util.Set<String> knownPhantoms) {
        // COMPUTE_MAXS, so the generated bodies get a valid frame size without
        // this code having to reason about argument widths.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        boolean isInterface = spec.isInterface();

        writer.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | (isInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0),
                spec.internalName(), null, "java/lang/Object", null);

        writer.visitSource("octo-phantom", null);

        for (Member field : spec.fields()) {
            FieldVisitor fieldVisitor = writer.visitField(
                    Opcodes.ACC_PUBLIC | (field.isStatic() ? Opcodes.ACC_STATIC : 0)
                            | (isInterface ? Opcodes.ACC_FINAL : 0),
                    field.name(), field.descriptor(), null, isInterface ? defaultConstant(field.descriptor()) : null);
            fieldVisitor.visitEnd();
        }

        boolean hasConstructor = false;

        for (Member method : spec.methods()) {
            if (method.name().equals("<init>")) {
                hasConstructor = method.descriptor().equals("()V");
            }

            emitMethod(writer, spec, method, isInterface, knownPhantoms);
        }

        // Always leave a no-argument constructor: other stand-ins call it when
        // they need to return an instance of this one.
        if (!isInterface && !hasConstructor) {
            emitMethod(writer, spec, new Member("<init>", "()V", false), false, knownPhantoms);
        }

        writer.visitEnd();
        LOG.debug("generated stand-in class {}", spec.internalName());
        return writer.toByteArray();
    }

    private static void emitMethod(ClassWriter writer, Spec spec, Member method, boolean isInterface,
            java.util.Set<String> knownPhantoms) {
        int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | (method.isStatic() ? Opcodes.ACC_STATIC : 0);

        if (isInterface && !method.isStatic()) {
            // An interface method with no body must be abstract; implementors supply it.
            writer.visitMethod(access | Opcodes.ACC_ABSTRACT, method.name(), method.descriptor(), null, null)
                    .visitEnd();
            return;
        }

        MethodVisitor visitor = writer.visitMethod(access, method.name(), method.descriptor(), null, null);
        visitor.visitCode();

        if (method.name().equals("<init>")) {
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        }

        Type returnType = Type.getReturnType(method.descriptor());

        if (returnType.getSort() == Type.OBJECT && knownPhantoms.contains(returnType.getInternalName())) {
            // The return type is itself a stand-in, so hand back a usable object.
            visitor.visitTypeInsn(Opcodes.NEW, returnType.getInternalName());
            visitor.visitInsn(Opcodes.DUP);
            visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, returnType.getInternalName(), "<init>", "()V", false);
            visitor.visitInsn(Opcodes.ARETURN);
            visitor.visitMaxs(0, 0);
            visitor.visitEnd();
            return;
        }

        switch (returnType.getSort()) {
            case Type.VOID -> visitor.visitInsn(Opcodes.RETURN);
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                visitor.visitInsn(Opcodes.ICONST_0);
                visitor.visitInsn(Opcodes.IRETURN);
            }
            case Type.LONG -> {
                visitor.visitInsn(Opcodes.LCONST_0);
                visitor.visitInsn(Opcodes.LRETURN);
            }
            case Type.FLOAT -> {
                visitor.visitInsn(Opcodes.FCONST_0);
                visitor.visitInsn(Opcodes.FRETURN);
            }
            case Type.DOUBLE -> {
                visitor.visitInsn(Opcodes.DCONST_0);
                visitor.visitInsn(Opcodes.DRETURN);
            }
            default -> {
                visitor.visitInsn(Opcodes.ACONST_NULL);
                visitor.visitInsn(Opcodes.ARETURN);
            }
        }

        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static Object defaultConstant(String descriptor) {
        return switch (Type.getType(descriptor).getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> 0;
            case Type.LONG -> 0L;
            case Type.FLOAT -> 0.0f;
            case Type.DOUBLE -> 0.0d;
            default -> null;
        };
    }
}
