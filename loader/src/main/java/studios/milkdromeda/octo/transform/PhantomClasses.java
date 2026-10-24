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
        /** Other stand-ins this one has to be assignable to, learned from how the mod uses it. */
        private final Set<String> supertypes = new LinkedHashSet<>();
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

        public Set<String> supertypes() {
            return supertypes;
        }

        @Override
        public String toString() {
            return internalName + " (" + methods.size() + " methods, " + fields.size() + " fields"
                    + (supertypes.isEmpty() ? "" : ", is a " + supertypes) + ")";
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
        observeShape(bytes, missing);
        observeAssignments(bytes, missing);
    }

    /**
     * Learns which stand-ins have to be subtypes of which.
     *
     * <p>Two missing classes are not unrelated just because nothing says how they
     * are related. Balm hands a {@code BlockCapability} to a method declared to
     * take a {@code BaseCapability}; two unrelated stand-ins with those names make
     * that a {@code VerifyError} for the whole class, which is a worse failure
     * than the missing classes themselves. The verifier's own question — "is this
     * value assignable to that parameter" — is asked here first, over every
     * method that touches a missing class, and each answer the JVM will need is
     * recorded so the stand-in can be generated to give it.
     */
    private void observeAssignments(byte[] bytes, Predicate<String> missing) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);

        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            if (method.instructions.size() == 0 || !touchesMissing(method, missing)) {
                continue;
            }

            try {
                new org.objectweb.asm.tree.analysis.Analyzer<>(new AssignmentRecorder(missing))
                        .analyze(node.name, method);
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException e) {
                // Old compilers emit the odd shape the analyser will not follow.
                // What was recorded before it gave up still stands.
                LOG.debug("could not follow {}.{}: {}", node.name, method.name, e.toString());
            }
        }
    }

    private static boolean touchesMissing(org.objectweb.asm.tree.MethodNode method, Predicate<String> missing) {
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
            String owner = instruction instanceof org.objectweb.asm.tree.MethodInsnNode call ? call.owner
                    : instruction instanceof org.objectweb.asm.tree.FieldInsnNode field ? field.owner
                    : instruction instanceof org.objectweb.asm.tree.TypeInsnNode type ? type.desc
                    : null;

            if (owner != null && missing.test(owner)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The verifier, minus the verdict: every "must be assignable to" between two
     * missing classes is written down instead of being checked.
     */
    private final class AssignmentRecorder extends org.objectweb.asm.tree.analysis.SimpleVerifier {
        private final Predicate<String> missing;

        AssignmentRecorder(Predicate<String> missing) {
            super(Opcodes.ASM9, null, null, null, false);
            this.missing = missing;
        }

        @Override
        protected boolean isSubTypeOf(org.objectweb.asm.tree.analysis.BasicValue value,
                org.objectweb.asm.tree.analysis.BasicValue expected) {
            Type actual = value.getType();
            Type wanted = expected.getType();

            if (actual != null && wanted != null && actual.getSort() == Type.OBJECT
                    && wanted.getSort() == Type.OBJECT && !actual.equals(wanted)
                    && missing.test(actual.getInternalName()) && missing.test(wanted.getInternalName())) {
                specFor(actual.getInternalName()).supertypes.add(wanted.getInternalName());
                specFor(wanted.getInternalName());
            }

            // Not a verifier: whatever the answer, the analysis carries on.
            return true;
        }

        @Override
        public org.objectweb.asm.tree.analysis.BasicValue merge(org.objectweb.asm.tree.analysis.BasicValue a,
                org.objectweb.asm.tree.analysis.BasicValue b) {
            if (a.equals(b)) {
                return a;
            }

            Type first = a.getType();
            Type second = b.getType();

            if (first != null && second != null && isReference(first) && isReference(second)) {
                // Merging two branches is not an assignment, so nothing is
                // recorded; the common type is simply "some object".
                return newValue(Type.getObjectType("java/lang/Object"));
            }

            return org.objectweb.asm.tree.analysis.BasicValue.UNINITIALIZED_VALUE;
        }

        private static boolean isReference(Type type) {
            return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
        }

        @Override
        protected boolean isInterface(Type type) {
            return false;
        }

        @Override
        protected Type getSuperClass(Type type) {
            return Type.getObjectType("java/lang/Object");
        }

        @Override
        protected boolean isAssignableFrom(Type type, Type other) {
            return true;
        }

        @Override
        protected Class<?> getClass(Type type) {
            throw new UnsupportedOperationException("stand-in planning never loads classes");
        }
    }

    private void observeShape(byte[] bytes, Predicate<String> missing) {
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

                        // A missing type that only ever appears in the signature
                        // of a missing method still has to exist: the verifier
                        // loads it to check what is passed against it.
                        for (Type argument : Type.getArgumentTypes(descriptor)) {
                            noteSignatureType(argument);
                        }

                        noteSignatureType(Type.getReturnType(descriptor));
                    }

                    private void noteSignatureType(Type type) {
                        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;

                        if (element.getSort() == Type.OBJECT && missing.test(element.getInternalName())) {
                            specFor(element.getInternalName());
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (!missing.test(owner)) {
                            return;
                        }

                        noteSignatureType(Type.getType(descriptor));

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
        Spec spec = specs.get(internalName);

        if (spec == null && internalName.endsWith(IMPLEMENTATION_SUFFIX)) {
            // The implementation behind a stand-in interface is planned along
            // with the interface; asking for it is asking for a class.
            Spec iface = specs.get(internalName.substring(0, internalName.length() - IMPLEMENTATION_SUFFIX.length()));

            if (iface != null && iface.isInterface()) {
                Spec impl = new Spec(internalName);
                impl.instantiated = true;
                return impl;
            }
        }

        return spec;
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
        return generateKnown(internalName);
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
        return generate(spec, Map.of(spec.internalName(), spec));
    }

    /** The suffix of the class generated to stand behind a stand-in interface. */
    public static final String IMPLEMENTATION_SUFFIX = "$OctoStandIn";

    /**
     * Builds the class file for one of the recorded specifications, or for the
     * implementation behind a stand-in interface.
     */
    private byte[] generateKnown(String internalName) {
        if (internalName.endsWith(IMPLEMENTATION_SUFFIX)) {
            Spec iface = specs.get(internalName.substring(0, internalName.length() - IMPLEMENTATION_SUFFIX.length()));

            if (iface != null && iface.isInterface()) {
                return generateImplementation(iface, specs);
            }
        }

        Spec spec = specs.get(internalName);

        if (spec == null) {
            throw new IllegalArgumentException("no stand-in was planned for " + internalName);
        }

        return generate(spec, specs);
    }

    /** Whether a name is a stand-in this set can produce, including interface implementations. */
    public boolean canGenerate(String internalName) {
        if (specs.containsKey(internalName)) {
            return true;
        }

        if (internalName.endsWith(IMPLEMENTATION_SUFFIX)) {
            Spec iface = specs.get(internalName.substring(0, internalName.length() - IMPLEMENTATION_SUFFIX.length()));
            return iface != null && iface.isInterface();
        }

        return false;
    }

    private static byte[] generate(Spec spec, Map<String, Spec> known) {
        // COMPUTE_MAXS, so the generated bodies get a valid frame size without
        // this code having to reason about argument widths.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        boolean isInterface = spec.isInterface();
        String superName = isInterface ? "java/lang/Object" : superclassOf(spec, known);
        java.util.List<String> interfaces = interfacesOf(spec, known);

        writer.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | (isInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0),
                spec.internalName(), null, superName, interfaces.isEmpty() ? null : interfaces.toArray(new String[0]));

        writer.visitSource("octo-phantom", null);

        boolean needsStaticInit = false;
        boolean needsInstanceInit = false;

        for (Member field : spec.fields()) {
            boolean primitive = isPrimitive(field.descriptor());
            FieldVisitor fieldVisitor = writer.visitField(
                    Opcodes.ACC_PUBLIC | (field.isStatic() ? Opcodes.ACC_STATIC : 0)
                            | (isInterface ? Opcodes.ACC_FINAL : 0),
                    field.name(), field.descriptor(), null,
                    isInterface && primitive ? defaultConstant(field.descriptor()) : null);
            fieldVisitor.visitEnd();

            if (!primitive) {
                if (field.isStatic() || isInterface) {
                    needsStaticInit = true;
                } else {
                    needsInstanceInit = true;
                }
            }
        }

        if (needsStaticInit) {
            emitStaticInitialiser(writer, spec, known, isInterface);
        }

        boolean hasConstructor = false;
        Set<String> emitted = new LinkedHashSet<>();

        for (Member method : spec.methods()) {
            if (method.name().equals("<clinit>")) {
                continue;
            }

            if (method.name().equals("<init>") && method.descriptor().equals("()V")) {
                hasConstructor = true;
            }

            if (emitted.add(method.name() + method.descriptor())) {
                emitMethod(writer, spec, method, isInterface, known, superName, needsInstanceInit);
            }
        }

        // A class standing in for something that has to be one of several
        // interfaces must actually answer their methods, or the first call is an
        // AbstractMethodError.
        if (!isInterface) {
            for (Member inherited : inheritedInterfaceMethods(spec, known)) {
                if (emitted.add(inherited.name() + inherited.descriptor())) {
                    emitMethod(writer, spec, inherited, false, known, superName, needsInstanceInit);
                }
            }
        }

        // Always leave a no-argument constructor: other stand-ins call it when
        // they need to return an instance of this one.
        if (!isInterface && !hasConstructor) {
            emitMethod(writer, spec, new Member("<init>", "()V", false), false, known, superName, needsInstanceInit);
        }

        writer.visitEnd();
        LOG.debug("generated stand-in class {}", spec.internalName());
        return writer.toByteArray();
    }

    /**
     * A plain class implementing a stand-in interface, so that a factory whose
     * return type is that interface has something to return other than null.
     */
    private static byte[] generateImplementation(Spec iface, Map<String, Spec> known) {
        Spec impl = new Spec(iface.internalName() + IMPLEMENTATION_SUFFIX);
        impl.instantiated = true;
        impl.supertypes.add(iface.internalName());

        for (Member method : iface.methods()) {
            if (!method.isStatic() && !method.name().startsWith("<")) {
                impl.methods.add(method);
            }
        }

        Map<String, Spec> withImpl = new java.util.HashMap<>(known);
        withImpl.put(impl.internalName(), impl);
        return generate(impl, withImpl);
    }

    /** The one stand-in class this one should extend, if the mod's usage asks for one. */
    private static String superclassOf(Spec spec, Map<String, Spec> known) {
        for (String candidate : spec.supertypes()) {
            Spec parent = known.get(candidate);

            if (parent != null && !parent.isInterface() && !extendsTransitively(parent, spec.internalName(), known,
                    new LinkedHashSet<>())) {
                return candidate;
            }
        }

        return "java/lang/Object";
    }

    private static boolean extendsTransitively(Spec spec, String target, Map<String, Spec> known, Set<String> seen) {
        if (!seen.add(spec.internalName())) {
            return true;
        }

        for (String parent : spec.supertypes()) {
            if (parent.equals(target)) {
                return true;
            }

            Spec next = known.get(parent);

            if (next != null && extendsTransitively(next, target, known, seen)) {
                return true;
            }
        }

        return false;
    }

    private static java.util.List<String> interfacesOf(Spec spec, Map<String, Spec> known) {
        java.util.List<String> out = new java.util.ArrayList<>();

        for (String candidate : spec.supertypes()) {
            Spec parent = known.get(candidate);

            if (parent != null && parent.isInterface() && !candidate.equals(spec.internalName())
                    && !extendsTransitively(parent, spec.internalName(), known, new LinkedHashSet<>())) {
                out.add(candidate);
            }
        }

        return out;
    }

    /** Every instance method of every stand-in interface a class takes on, however indirectly. */
    private static Set<Member> inheritedInterfaceMethods(Spec spec, Map<String, Spec> known) {
        Set<Member> out = new LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(interfacesOf(spec, known));
        Set<String> seen = new LinkedHashSet<>();

        while (!queue.isEmpty()) {
            Spec iface = known.get(queue.poll());

            if (iface == null || !seen.add(iface.internalName())) {
                continue;
            }

            for (Member method : iface.methods()) {
                if (!method.isStatic() && !method.name().startsWith("<")) {
                    out.add(method);
                }
            }

            queue.addAll(interfacesOf(iface, known));
        }

        return out;
    }

    private static void emitStaticInitialiser(ClassWriter writer, Spec spec, Map<String, Spec> known,
            boolean isInterface) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        visitor.visitCode();

        for (Member field : spec.fields()) {
            if ((field.isStatic() || isInterface) && !isPrimitive(field.descriptor())) {
                pushValue(visitor, spec, Type.getType(field.descriptor()), known, false);
                visitor.visitFieldInsn(Opcodes.PUTSTATIC, spec.internalName(), field.name(), field.descriptor());
            }
        }

        visitor.visitInsn(Opcodes.RETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void emitMethod(ClassWriter writer, Spec spec, Member method, boolean isInterface,
            Map<String, Spec> known, String superName, boolean initialiseFields) {
        int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | (method.isStatic() ? Opcodes.ACC_STATIC : 0);

        if (isInterface && !method.isStatic()) {
            // A default method rather than an abstract one: mods implement these
            // interfaces with lambdas and anonymous classes that only override
            // what they knew about, and the stand-in's own implementation class
            // has to answer everything.
            MethodVisitor visitor = writer.visitMethod(access, method.name(), method.descriptor(), null, null);
            visitor.visitCode();
            pushReturn(visitor, spec, method, known, true);
            visitor.visitMaxs(0, 0);
            visitor.visitEnd();
            return;
        }

        MethodVisitor visitor = writer.visitMethod(access, method.name(), method.descriptor(), null, null);
        visitor.visitCode();

        if (method.name().equals("<init>")) {
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);

            if (initialiseFields) {
                for (Member field : spec.fields()) {
                    if (!field.isStatic() && !isPrimitive(field.descriptor())) {
                        visitor.visitVarInsn(Opcodes.ALOAD, 0);
                        pushValue(visitor, spec, Type.getType(field.descriptor()), known, false);
                        visitor.visitFieldInsn(Opcodes.PUTFIELD, spec.internalName(), field.name(),
                                field.descriptor());
                    }
                }
            }

            visitor.visitInsn(Opcodes.RETURN);
            visitor.visitMaxs(0, 0);
            visitor.visitEnd();
            return;
        }

        pushReturn(visitor, spec, method, known, !method.isStatic());
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void pushReturn(MethodVisitor visitor, Spec spec, Member method, Map<String, Spec> known,
            boolean hasThis) {
        Type returnType = Type.getReturnType(method.descriptor());

        if (returnType.getSort() == Type.VOID) {
            visitor.visitInsn(Opcodes.RETURN);
            return;
        }

        // A builder: an instance method returning its own type hands back the
        // receiver, so builder.comment(..).define(..) keeps going instead of
        // failing on the second link.
        if (hasThis && returnType.getSort() == Type.OBJECT && isSelfType(spec, returnType.getInternalName(), known)) {
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitInsn(Opcodes.ARETURN);
            return;
        }

        pushValue(visitor, spec, returnType, known, true);
        visitor.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
    }

    private static boolean isSelfType(Spec spec, String type, Map<String, Spec> known) {
        if (type.equals(spec.internalName())) {
            return true;
        }

        return extendsTransitively(spec, type, known, new LinkedHashSet<>()) && known.containsKey(type);
    }

    /**
     * Pushes something usable of {@code type}.
     *
     * <p>A stand-in class gets a fresh instance of itself and a stand-in
     * interface an instance of its implementation, so chained calls keep going.
     * A loader event bus gets a live one: {@code NeoForge.EVENT_BUS.register(..)}
     * in a mod constructor against a null field is a dead mod, against a working
     * bus it is a mod that loaded. Everything else is {@link Nothing}'s answer.
     */
    private static void pushValue(MethodVisitor visitor, Spec owner, Type type, Map<String, Spec> known,
            boolean allowNull) {
        if (type.getSort() == Type.OBJECT) {
            String name = type.getInternalName();
            Spec target = known.get(name);

            if (target != null) {
                String concrete = target.isInterface() ? name + IMPLEMENTATION_SUFFIX : name;
                visitor.visitTypeInsn(Opcodes.NEW, concrete);
                visitor.visitInsn(Opcodes.DUP);
                visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, concrete, "<init>", "()V", false);
                return;
            }

            if (Nothing.pushLive(visitor, type, owner.internalName())) {
                return;
            }
        }

        Nothing.push(visitor, type);
    }

    private static boolean isPrimitive(String descriptor) {
        int sort = Type.getType(descriptor).getSort();
        return sort != Type.OBJECT && sort != Type.ARRAY;
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
