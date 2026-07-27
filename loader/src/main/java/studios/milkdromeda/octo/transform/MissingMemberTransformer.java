package studios.milkdromeda.octo.transform;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Answers for the parts of the four loaders' APIs that Octo has not implemented.
 *
 * <p>{@link PhantomClasses} covers a class that is not there at all. This covers
 * the other half, which turns out to be the more common one: a class Octo
 * <em>does</em> provide, missing one method the mod happens to call. That is a
 * {@code NoSuchMethodError} at the call site, and because so many of these calls
 * happen during a mixin configuration plugin or a mod's static initialiser, one
 * absent accessor takes down the whole launch. Create asking
 * {@code LoadingModList.get().getModFileById(id)} from an enum's constructor is
 * exactly that shape: a null answer would have meant "that mod is not here" and
 * been completely fine, but the method did not exist, so nothing loaded at all.
 *
 * <p>So calls into Octo's own loader-API packages are checked as the class is
 * read, and a call to something genuinely absent is rewritten to discard its
 * arguments and produce a default. This can only fire where the JVM was about to
 * throw anyway — the member is looked up through the same class loader that
 * would have resolved it — so the choice is not between a correct call and a
 * neutered one, it is between a neutered call and a dead game. Everything
 * rewritten is logged and recorded on the mod's compatibility report, because
 * this is a gap in Octo that someone should come back and fill.
 */
public final class MissingMemberTransformer implements Transformer {
    private static final OctoLog LOG = OctoLog.of(MissingMemberTransformer.class);

    /**
     * The packages whose contents are Octo's to implement.
     *
     * <p>Deliberately only these. A missing member on a Minecraft class is the
     * time capsule's business and is handled by the API rules; a missing member
     * on a mod's own class is a bug in that mod that Octo must not hide.
     *
     * <p>These overlap the parent-first list in {@code OctoClassLoader} without
     * being derived from it: that list decides which copy of a class wins, this
     * one decides whose API gaps Octo is answerable for, and they are allowed to
     * disagree at the edges.
     */
    private static final List<String> LOADER_API_PACKAGES = List.of(
            "net/fabricmc/api/",
            "net/fabricmc/loader/",
            "org/quiltmc/loader/",
            "org/quiltmc/qsl/",
            "net/neoforged/fml/",
            "net/neoforged/bus/",
            "net/neoforged/api/",
            "net/minecraftforge/fml/",
            "net/minecraftforge/eventbus/",
            "net/minecraftforge/api/",
            "cpw/mods/fml/",
            "com/mumfrey/liteloader/");

    /** Where Octo's own copy of those APIs lives. */
    private final ClassLoader apiLoader;

    private final Map<String, Object> resolved = new ConcurrentHashMap<>();
    private final Map<String, Boolean> members = new ConcurrentHashMap<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private static final Object ABSENT = new Object();

    public MissingMemberTransformer(ClassLoader apiLoader) {
        this.apiLoader = apiLoader;
    }

    /** Everything this transformer had to stand in for, for the launch summary. */
    public Set<String> gaps() {
        return new LinkedHashSet<>(reported);
    }

    @Override
    public String name() {
        return "loader-api-gaps";
    }

    /**
     * Skips the classes that cannot possibly be calling a loader API.
     *
     * <p>This is installed globally, so it is asked about every one of the tens
     * of thousands of classes a modded game loads. The game's own classes are
     * the bulk of those and never call into a loader; Octo's API classes are
     * loaded by the parent and never reach here at all, but naming them costs
     * nothing and documents the boundary.
     */
    @Override
    public boolean handles(String className, TransformContext context) {
        for (String prefix : NEVER_CALLS_LOADER_API) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }

        return !isLoaderApi(className);
    }

    private static final List<String> NEVER_CALLS_LOADER_API = List.of(
            "net/minecraft/", "com/mojang/", "java/", "javax/", "jdk/", "sun/",
            "org/objectweb/asm/", "org/spongepowered/asm/", "studios/milkdromeda/octo/");

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        // A class that never names one of these packages cannot call into it, and
        // this runs over every class the game loads, so the cheap answer first.
        if (!mentionsLoaderApi(bytes)) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        Rewriter rewriter = new Rewriter(writer, context, className);
        reader.accept(rewriter, 0);
        return rewriter.changed ? writer.toByteArray() : bytes;
    }

    /**
     * The distinctive middle of each guarded package, as raw bytes.
     *
     * <p>Every reference to a class in one of those packages puts its internal
     * name in the constant pool as UTF-8, so finding one of these in the class
     * file answers "could this possibly call into Octo's API" without parsing
     * anything. They are the shortest fragments that still discriminate:
     * {@code neoforged/} covers all three NeoForge packages, {@code fml/} covers
     * both Forge spellings and Octo's own FML surface.
     */
    private static final byte[][] MARKERS = {
            "fabricmc/".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "fml/".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "quiltmc/".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "neoforged/".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "minecraftforge/".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "liteloader/".getBytes(java.nio.charset.StandardCharsets.UTF_8),
    };

    /** Which markers can start at a given byte, so the scan is one pass with an index. */
    private static final boolean[] MARKER_STARTS = new boolean[256];

    static {
        for (byte[] marker : MARKERS) {
            MARKER_STARTS[marker[0] & 0xFF] = true;
        }
    }

    private static boolean mentionsLoaderApi(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (!MARKER_STARTS[bytes[i] & 0xFF]) {
                continue;
            }

            for (byte[] marker : MARKERS) {
                if (matchesAt(bytes, i, marker)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean matchesAt(byte[] haystack, int offset, byte[] needle) {
        if (offset + needle.length > haystack.length) {
            return false;
        }

        for (int i = 0; i < needle.length; i++) {
            if (haystack[offset + i] != needle[i]) {
                return false;
            }
        }

        return true;
    }

    private static boolean isLoaderApi(String internalName) {
        for (String prefix : LOADER_API_PACKAGES) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * @return the class Octo would resolve for this name, or {@code null} when
     *         nothing has it — in which case this is a missing <em>class</em>,
     *         which is {@link PhantomClasses}' problem and not this one
     */
    private Class<?> resolve(String internalName) {
        Object cached = resolved.computeIfAbsent(internalName, name -> {
            try {
                // Never initialised: resolving a type must not run somebody's
                // static block from inside a class load.
                return Class.forName(name.replace('/', '.'), false, apiLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                return ABSENT;
            }
        });

        return cached == ABSENT ? null : (Class<?>) cached;
    }

    private boolean hasMethod(String owner, String name, String descriptor) {
        return members.computeIfAbsent(owner + "." + name + descriptor, ignored -> {
            Class<?> type = resolve(owner);

            try {
                return type == null || declaresMethod(type, name, descriptor, new LinkedHashSet<>());
            } catch (LinkageError e) {
                // Reflecting over a class whose signatures name something absent
                // throws. That is not evidence the method is missing, so the call
                // is left exactly as the mod compiled it.
                LOG.debug("could not inspect {}: {}", owner, e.toString());
                return true;
            }
        });
    }

    private static boolean declaresMethod(Class<?> type, String name, String descriptor, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) {
            return false;
        }

        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(descriptor)) {
                return true;
            }
        }

        if (declaresMethod(type.getSuperclass(), name, descriptor, seen)) {
            return true;
        }

        for (Class<?> implemented : type.getInterfaces()) {
            if (declaresMethod(implemented, name, descriptor, seen)) {
                return true;
            }
        }

        // Everything answers Object's methods, including interfaces.
        return type.isInterface() && declaresMethod(Object.class, name, descriptor, seen);
    }

    private boolean hasField(String owner, String name, String descriptor) {
        return members.computeIfAbsent(owner + "#" + name + ":" + descriptor, ignored -> {
            Class<?> type = resolve(owner);

            try {
                return type == null || declaresField(type, name, descriptor, new LinkedHashSet<>());
            } catch (LinkageError e) {
                LOG.debug("could not inspect {}: {}", owner, e.toString());
                return true;
            }
        });
    }

    private static boolean declaresField(Class<?> type, String name, String descriptor, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) {
            return false;
        }

        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name) && Type.getDescriptor(field.getType()).equals(descriptor)) {
                return true;
            }
        }

        if (declaresField(type.getSuperclass(), name, descriptor, seen)) {
            return true;
        }

        for (Class<?> implemented : type.getInterfaces()) {
            if (declaresField(implemented, name, descriptor, seen)) {
                return true;
            }
        }

        return false;
    }

    private void record(TransformContext context, String owner, String member, String className) {
        String gap = owner.replace('/', '.') + "." + member;

        if (reported.add(gap)) {
            LOG.warn("{}: Octo does not implement {}; calls to it return a default", context.modId(), gap);
        }

        context.note("stood in for missing loader API " + gap + " (called from " + className.replace('/', '.') + ")");
    }

    private final class Rewriter extends ClassVisitor {
        private final TransformContext context;
        private final String className;
        boolean changed;

        Rewriter(ClassVisitor delegate, TransformContext context, String className) {
            super(Opcodes.ASM9, delegate);
            this.context = context;
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

            return delegate == null ? null : new MethodVisitor(Opcodes.ASM9, delegate) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                        boolean isInterface) {
                    // A constructor call leaves an uninitialised receiver on the
                    // stack that only the constructor can resolve, and a super
                    // call is part of the class's own shape. Neither can be
                    // rewritten into a default, so neither is touched.
                    if (opcode == Opcodes.INVOKESPECIAL || name.charAt(0) == '<'
                            || !isLoaderApi(owner) || hasMethod(owner, name, descriptor)) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        return;
                    }

                    discard(Type.getArgumentTypes(descriptor), opcode != Opcodes.INVOKESTATIC);
                    pushDefault(Type.getReturnType(descriptor));
                    changed = true;
                    record(context, owner, name + descriptor, className);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    if (!isLoaderApi(owner) || hasField(owner, name, descriptor)) {
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                        return;
                    }

                    Type type = Type.getType(descriptor);

                    switch (opcode) {
                        case Opcodes.GETSTATIC -> pushDefault(type);
                        case Opcodes.GETFIELD -> {
                            pop(Type.getType("Ljava/lang/Object;"));
                            pushDefault(type);
                        }
                        case Opcodes.PUTSTATIC -> pop(type);
                        default -> {
                            pop(type);
                            pop(Type.getType("Ljava/lang/Object;"));
                        }
                    }

                    changed = true;
                    record(context, owner, name, className);
                }

                /** Removes a call's operands, deepest last, plus the receiver. */
                private void discard(Type[] arguments, boolean hasReceiver) {
                    for (int i = arguments.length - 1; i >= 0; i--) {
                        pop(arguments[i]);
                    }

                    if (hasReceiver) {
                        super.visitInsn(Opcodes.POP);
                    }
                }

                private void pop(Type type) {
                    super.visitInsn(type.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                }

                private void pushDefault(Type type) {
                    switch (type.getSort()) {
                        case Type.VOID -> {
                        }
                        case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT ->
                                super.visitInsn(Opcodes.ICONST_0);
                        case Type.LONG -> super.visitInsn(Opcodes.LCONST_0);
                        case Type.FLOAT -> super.visitInsn(Opcodes.FCONST_0);
                        case Type.DOUBLE -> super.visitInsn(Opcodes.DCONST_0);
                        default -> super.visitInsn(Opcodes.ACONST_NULL);
                    }
                }
            };
        }
    }
}
