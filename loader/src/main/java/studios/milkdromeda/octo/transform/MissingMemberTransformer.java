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
 * Repairs calls to members that are not where the caller expects them.
 *
 * <p>{@link PhantomClasses} covers a class that is not there at all. This covers
 * the other half, which turns out to be the more common one: a class that
 * <em>is</em> there, missing one method the caller happens to name, or carrying
 * it in a different shape. Each of those is a {@code LinkageError} at the call
 * site, and because so many of these calls happen during a mixin configuration
 * plugin or a mod's static initialiser, one absent accessor takes down the whole
 * launch. Create asking {@code LoadingModList.get().getModFileById(id)} from an
 * enum's constructor is exactly that shape.
 *
 * <p>Calls are checked as the calling class is read, and repaired in the
 * cheapest way that keeps the caller running:
 *
 * <ul>
 *   <li>a member that moved between static and instance — NeoForge's
 *       {@code FMLLoader.getDist()} became {@code FMLLoader.getCurrent().getDist()}
 *       — is reached the new way;</li>
 *   <li>a class that became an interface, or stopped being one, is called with
 *       the instruction its new shape needs;</li>
 *   <li>a member that is genuinely absent is not called: its arguments are
 *       discarded and a default stands in for its result — and a missing builder
 *       method hands back the builder, so the chain goes on.</li>
 * </ul>
 *
 * <p>All of it can only fire where the JVM was about to throw anyway, so the
 * choice is not between a correct call and a repaired one, it is between a
 * repaired call and a dead game. Everything repaired is logged and recorded on
 * the mod's compatibility report.
 *
 * <p>Two instances run. One guards the four loaders' APIs, for every mod — those
 * gaps are Octo's own. The other guards the game itself, and only for mods built
 * for another Minecraft version, which is where "the game no longer has this" is
 * an expected answer rather than a bug to surface.
 */
public final class MissingMemberTransformer implements Transformer {
    private static final OctoLog LOG = OctoLog.of(MissingMemberTransformer.class);

    /**
     * The packages whose contents are Octo's to implement.
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
            "net/neoforged/neoforgespi/",
            "net/minecraftforge/fml/",
            "net/minecraftforge/eventbus/",
            "net/minecraftforge/api/",
            "net/minecraftforge/forgespi/",
            "cpw/mods/fml/",
            "com/mumfrey/liteloader/",
            // Maven's, not a loader's, but Forge chose it for the return type of
            // IModInfo.getVersion(), so a gap in it lands in a mod exactly like a
            // gap in FML does.
            "org/apache/maven/artifact/versioning/");

    /** The game's own packages, guarded for mods from other versions only. */
    private static final List<String> GAME_PACKAGES = List.of(
            "net/minecraft/", "com/mojang/");

    /** How to find out what a class really has. */
    interface Lookup {
        /** Whether calls on this owner are this instance's business at all. */
        boolean guards(String owner);

        /** @return whether the method exists, or {@code null} when that cannot be told */
        Boolean hasMethod(String owner, String name, String descriptor);

        /** @return whether the method found is static, or {@code null} when it is not found */
        Boolean isStatic(String owner, String name, String descriptor);

        Boolean hasField(String owner, String name, String descriptor);

        /** @return whether the owner is an interface, or {@code null} when it is not here */
        Boolean isInterface(String owner);
    }

    private final Lookup lookup;
    private final boolean translatedModsOnly;
    /** Classes outside any mod that the game-side bypass also covers. */
    private final List<String> alsoCovered;
    private final String name;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /** Guards the four loaders' APIs, resolved through Octo's own class loader. */
    public MissingMemberTransformer(ClassLoader apiLoader) {
        this(new ReflectiveLookup(apiLoader), false, List.of(), "loader-api-gaps");
    }

    private MissingMemberTransformer(Lookup lookup, boolean translatedModsOnly, List<String> alsoCovered,
            String name) {
        this.lookup = lookup;
        this.translatedModsOnly = translatedModsOnly;
        this.alsoCovered = List.copyOf(alsoCovered);
        this.name = name;
    }

    /**
     * Guards the game for mods built for another version: what this Minecraft
     * no longer has is bypassed instead of being a crash, which is the last of
     * Octo's answers after renaming, rule-based migration and stand-ins.
     */
    public static MissingMemberTransformer forTranslatedMods(MemberIndex index) {
        return forTranslatedMods(index, List.of());
    }

    /**
     * @param alsoCovered packages outside any mod written for a different game
     *                    than this one — the NeoForge and Forge API jars, which
     *                    expect a game their installer patched
     */
    public static MissingMemberTransformer forTranslatedMods(MemberIndex index, List<String> alsoCovered) {
        return new MissingMemberTransformer(new IndexLookup(index), true, alsoCovered, "game-gaps");
    }

    /** Everything this transformer had to stand in for, for the launch summary. */
    public Set<String> gaps() {
        return new LinkedHashSet<>(reported);
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Skips the classes that cannot possibly need this.
     *
     * <p>This is installed globally, so it is asked about every one of the tens
     * of thousands of classes a modded game loads. The game's own classes are
     * the bulk of those and are consistent with themselves.
     */
    @Override
    public boolean handles(String className, TransformContext context) {
        if (translatedModsOnly && (context.mod() == null || !context.mod().isFromAnotherVersion())
                && !isAlsoCovered(className)) {
            return false;
        }

        for (String prefix : NEVER_NEEDS_REPAIR) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }

        return !isLoaderApi(className);
    }

    private static final List<String> NEVER_NEEDS_REPAIR = List.of(
            "net/minecraft/", "com/mojang/", "java/", "javax/", "jdk/", "sun/",
            "org/objectweb/asm/", "org/spongepowered/asm/", "studios/milkdromeda/octo/");

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        // A class that never names one of these packages cannot call into it, and
        // this runs over every class the game loads, so the cheap answer first.
        if (!(translatedModsOnly ? MENTIONS_GAME : MENTIONS_LOADER_API).matches(bytes)) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        Rewriter rewriter = new Rewriter(writer, context, className);
        reader.accept(rewriter, 0);
        return rewriter.changed ? writer.toByteArray() : bytes;
    }

    /**
     * The distinctive middle of each guarded package.
     *
     * <p>Every reference to a class in one of those packages puts its internal
     * name in the constant pool as UTF-8, so finding one of these in the class
     * file answers "could this possibly call into it" without parsing anything.
     */
    private static final ByteScan MENTIONS_LOADER_API = new ByteScan(List.of(
            "fabricmc/", "fml/", "quiltmc/", "neoforged/", "minecraftforge/", "liteloader/",
            "artifact/versioning/"));

    private static final ByteScan MENTIONS_GAME = new ByteScan(List.of("net/minecraft/", "com/mojang/"));

    private boolean isAlsoCovered(String className) {
        for (String prefix : alsoCovered) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    static boolean isLoaderApi(String internalName) {
        for (String prefix : LOADER_API_PACKAGES) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isGame(String internalName) {
        for (String prefix : GAME_PACKAGES) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    private void record(TransformContext context, String owner, String member, String className, String how) {
        String gap = owner.replace('/', '.') + "." + member;

        if (reported.add(gap)) {
            if (translatedModsOnly) {
                LOG.info("{}: this Minecraft has no {}; {}", context.modId(), gap, how);
            } else {
                LOG.warn("{}: Octo does not implement {}; {}", context.modId(), gap, how);
            }
        }

        context.note((translatedModsOnly ? "bypassed missing game member " : "stood in for missing loader API ")
                + gap + " (called from " + className.replace('/', '.') + ")");
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
        public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor, String signature,
                String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, methodName, methodDescriptor, signature, exceptions);

            return delegate == null ? null : new MethodVisitor(Opcodes.ASM9, delegate) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                        boolean isInterface) {
                    // A constructor call leaves an uninitialised receiver on the
                    // stack that only the constructor can resolve, and a super
                    // call is part of the class's own shape. Neither can be
                    // rewritten into a default, so neither is touched.
                    if (opcode == Opcodes.INVOKESPECIAL || name.charAt(0) == '<' || !lookup.guards(owner)
                            || owner.equals(className)) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        return;
                    }

                    Boolean exists = lookup.hasMethod(owner, name, descriptor);

                    if (exists == null) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        return;
                    }

                    if (exists) {
                        repairShape(opcode, owner, name, descriptor, isInterface);
                        return;
                    }

                    Type returnType = Type.getReturnType(descriptor);
                    boolean hasReceiver = opcode != Opcodes.INVOKESTATIC;

                    if (hasReceiver && returnsReceiver(owner, returnType)) {
                        // A builder method this version does not have: drop the
                        // arguments and hand the builder back, so the chain goes on.
                        discard(Type.getArgumentTypes(descriptor), false);
                        changed = true;
                        record(context, owner, name + descriptor, className, "the call hands back its receiver");
                        return;
                    }

                    discard(Type.getArgumentTypes(descriptor), hasReceiver);
                    pushDefault(returnType, owner);
                    changed = true;
                    record(context, owner, name + descriptor, className, "calls to it return a default");
                }

                /**
                 * The method exists. Whether it can be reached the way the caller
                 * reaches it is another matter: static against instance, class
                 * against interface.
                 */
                private void repairShape(int opcode, String owner, String name, String descriptor,
                        boolean isInterface) {
                    Boolean ownerIsInterface = lookup.isInterface(owner);
                    Boolean isStatic = lookup.isStatic(owner, name, descriptor);
                    boolean zeroArguments = Type.getArgumentTypes(descriptor).length == 0;
                    boolean wantsStatic = opcode == Opcodes.INVOKESTATIC;

                    if (isStatic != null && isStatic != wantsStatic && zeroArguments) {
                        if (isStatic) {
                            // Called on an instance, now static: the receiver is
                            // not needed.
                            super.visitInsn(Opcodes.POP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor,
                                    Boolean.TRUE.equals(ownerIsInterface));
                            changed = true;
                            record(context, owner, name + descriptor, className, "it is static now");
                            return;
                        }

                        String singleton = singletonAccessor(owner);

                        if (singleton != null) {
                            // Called statically, now an instance method on a
                            // singleton: reach the instance first.
                            boolean itf = Boolean.TRUE.equals(ownerIsInterface);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, singleton, "()L" + owner + ";", itf);
                            super.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, name,
                                    descriptor, itf);
                            changed = true;
                            record(context, owner, name + descriptor, className,
                                    "it is reached through " + singleton + "() now");
                            return;
                        }
                    }

                    if (ownerIsInterface != null && ownerIsInterface != isInterface) {
                        int fixed = opcode;

                        if (opcode == Opcodes.INVOKEVIRTUAL && ownerIsInterface) {
                            fixed = Opcodes.INVOKEINTERFACE;
                        } else if (opcode == Opcodes.INVOKEINTERFACE && !ownerIsInterface) {
                            fixed = Opcodes.INVOKEVIRTUAL;
                        }

                        super.visitMethodInsn(fixed, owner, name, descriptor, ownerIsInterface);
                        changed = true;
                        record(context, owner, name + descriptor, className,
                                ownerIsInterface ? "its class became an interface" : "its interface became a class");
                        return;
                    }

                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    if (!lookup.guards(owner) || owner.equals(className)
                            || !Boolean.FALSE.equals(lookup.hasField(owner, name, descriptor))) {
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                        return;
                    }

                    Type type = Type.getType(descriptor);

                    switch (opcode) {
                        case Opcodes.GETSTATIC -> pushDefault(type, owner);
                        case Opcodes.GETFIELD -> {
                            pop(Type.getType("Ljava/lang/Object;"));
                            pushDefault(type, owner);
                        }
                        case Opcodes.PUTSTATIC -> pop(type);
                        default -> {
                            pop(type);
                            pop(Type.getType("Ljava/lang/Object;"));
                        }
                    }

                    changed = true;
                    record(context, owner, name, className, "reads return a default and writes go nowhere");
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

                /**
                 * What the call produces now that it is not being made.
                 *
                 * <p>Emitted on the delegate rather than on this visitor: what
                 * {@link Nothing} writes is Octo's own, and has no business
                 * being read back through the rewrite that asked for it.
                 */
                private void pushDefault(Type type, String owner) {
                    if (type.getSort() == Type.OBJECT && Nothing.pushLive(delegate, type, owner)) {
                        return;
                    }

                    Nothing.push(delegate, type);
                }
            };
        }

        /** Whether a call on {@code owner} returning {@code type} is a builder handing itself back. */
        private boolean returnsReceiver(String owner, Type type) {
            return type.getSort() == Type.OBJECT && type.getInternalName().equals(owner);
        }

        /** The static method a singleton hands out its instance through, if there is one. */
        private String singletonAccessor(String owner) {
            for (String candidate : List.of("getCurrent", "get", "getInstance", "instance")) {
                if (Boolean.TRUE.equals(lookup.isStatic(owner, candidate, "()L" + owner + ";"))) {
                    return candidate;
                }
            }

            return null;
        }
    }

    /** Resolves the loader APIs by reflection over Octo's own copy of them. */
    private static final class ReflectiveLookup implements Lookup {
        private static final Object ABSENT = new Object();

        private final ClassLoader apiLoader;
        private final Map<String, Object> resolved = new ConcurrentHashMap<>();

        ReflectiveLookup(ClassLoader apiLoader) {
            this.apiLoader = apiLoader;
        }

        @Override
        public boolean guards(String owner) {
            return isLoaderApi(owner);
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

        @Override
        public Boolean hasMethod(String owner, String name, String descriptor) {
            Class<?> type = resolve(owner);

            if (type == null) {
                return null;
            }

            try {
                return findMethod(type, name, descriptor, new LinkedHashSet<>()) != null;
            } catch (LinkageError e) {
                // Reflecting over a class whose signatures name something absent
                // throws. That is not evidence the method is missing, so the call
                // is left exactly as the mod compiled it.
                LOG.debug("could not inspect {}: {}", owner, e.toString());
                return null;
            }
        }

        @Override
        public Boolean isStatic(String owner, String name, String descriptor) {
            Class<?> type = resolve(owner);

            if (type == null) {
                return null;
            }

            try {
                java.lang.reflect.Method method = findMethod(type, name, descriptor, new LinkedHashSet<>());
                return method == null ? null : java.lang.reflect.Modifier.isStatic(method.getModifiers());
            } catch (LinkageError e) {
                return null;
            }
        }

        @Override
        public Boolean hasField(String owner, String name, String descriptor) {
            Class<?> type = resolve(owner);

            if (type == null) {
                return null;
            }

            try {
                return declaresField(type, name, descriptor, new LinkedHashSet<>());
            } catch (LinkageError e) {
                LOG.debug("could not inspect {}: {}", owner, e.toString());
                return null;
            }
        }

        @Override
        public Boolean isInterface(String owner) {
            Class<?> type = resolve(owner);
            return type == null ? null : type.isInterface();
        }

        private static java.lang.reflect.Method findMethod(Class<?> type, String name, String descriptor,
                Set<Class<?>> seen) {
            if (type == null || !seen.add(type)) {
                return null;
            }

            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(descriptor)) {
                    return method;
                }
            }

            java.lang.reflect.Method found = findMethod(type.getSuperclass(), name, descriptor, seen);

            if (found != null) {
                return found;
            }

            for (Class<?> implemented : type.getInterfaces()) {
                found = findMethod(implemented, name, descriptor, seen);

                if (found != null) {
                    return found;
                }
            }

            // Everything answers Object's methods, including interfaces.
            return type.isInterface() ? findMethod(Object.class, name, descriptor, seen) : null;
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
    }

    /** Resolves the game from its class files, never loading anything. */
    private static final class IndexLookup implements Lookup {
        private final MemberIndex index;

        IndexLookup(MemberIndex index) {
            this.index = index;
        }

        /**
         * Only game classes the index can see the whole of: an owner whose
         * ancestry runs into something unreadable may inherit the member from
         * there, and a call is never neutered on a guess.
         */
        @Override
        public boolean guards(String owner) {
            return isGame(owner) && index.exists(owner) && index.isFullyKnown(owner);
        }

        @Override
        public Boolean hasMethod(String owner, String name, String descriptor) {
            return index.hasMethod(owner, name, descriptor);
        }

        @Override
        public Boolean isStatic(String owner, String name, String descriptor) {
            return index.isStaticMethod(owner, name, descriptor);
        }

        @Override
        public Boolean hasField(String owner, String name, String descriptor) {
            return index.hasField(owner, name, descriptor);
        }

        @Override
        public Boolean isInterface(String owner) {
            return index.isInterface(owner);
        }
    }
}
