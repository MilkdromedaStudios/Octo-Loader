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
 *   <li>a method or constructor whose parameters were widened — {@code Vector3f}
 *       to {@code Vector3fc} — is called in its new shape, the arguments being
 *       just as good as before;</li>
 *   <li>a constructor that is gone builds its object through one this version
 *       has, because the caller goes on to use what it made;</li>
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
    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String GAP_BRIDGE = "studios/milkdromeda/octo/transform/GapBridge";
    private static final String COPPER_COLLECTION = "net/minecraft/world/level/block/WeatheringCopperCollection";
    private static final List<String> WEATHER_STATES = List.of("exposed", "weathered", "oxidized");
    private static final String COLOUR_COLLECTION = "net/minecraft/world/level/block/ColorCollection";
    /** The dye colours, as field names spell them. */
    private static final List<String> COLOURS = List.of("light_blue", "light_gray", "white", "orange", "magenta",
            "yellow", "lime", "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final String REGISTRY = "Lnet/minecraft/core/Registry;";

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

    /** How to find out what a class really has. */
    interface Lookup {
        /** Whether calls on this owner are this instance's business at all. */
        boolean guards(String owner);

        /** A class's superclass, or {@code null} when it cannot be seen. */
        default String superName(String owner) {
            return null;
        }

        /** @return whether the method exists, or {@code null} when that cannot be told */
        Boolean hasMethod(String owner, String name, String descriptor);

        /** @return whether the method found is static, or {@code null} when it is not found */
        Boolean isStatic(String owner, String name, String descriptor);

        Boolean hasField(String owner, String name, String descriptor);

        /** @return whether the owner is an interface, or {@code null} when it is not here */
        Boolean isInterface(String owner);

        /** The descriptors the owner answers this name with; constructors are only its own. */
        List<String> overloads(String owner, String name);

        /** Whether a {@code type} can be passed where {@code ancestor} is expected. */
        boolean isAssignable(String type, String ancestor);

        /** @return the access flags of the method a call reaches, or {@code null} when unknown */
        default Integer methodAccess(String owner, String name, String descriptor) {
            return null;
        }

        /** @return the access flags of the field an instruction reaches, or {@code null} when unknown */
        default Integer fieldAccess(String owner, String name, String descriptor) {
            return null;
        }

        /** The owner's public static methods taking these parameters and returning the owner. */
        default List<String> staticFactories(String owner, String parameters) {
            return List.of();
        }

        /**
         * Whether the owner is nowhere at all: not something this lookup can
         * see, and not on the class path either. Its members are then as
         * missing as members get.
         */
        default boolean isAbsent(String owner, TransformContext context) {
            return false;
        }
    }

    private final Lookup lookup;
    private volatile studios.milkdromeda.octo.compat.api.Equivalents equivalents =
            studios.milkdromeda.octo.compat.api.Equivalents.EMPTY;
    private final Map<studios.milkdromeda.octo.runtime.LoadedMod, Set<String>> ownClasses = new ConcurrentHashMap<>();
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

        // The loader API jars' own packages are game-side too: a translated
        // Forge mod whose main class names only MinecraftForge and never
        // net/minecraft/ still reaches into what Forge 66 changed.
        List<String> mentions = new java.util.ArrayList<>(List.of("net/minecraft/", "com/mojang/"));
        mentions.addAll(alsoCovered);
        this.mentionsGame = new ByteScan(mentions);
    }

    private final ByteScan mentionsGame;

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

    /**
     * Consults the version-equivalents table before giving up on a member: a
     * method renamed between versions is called by its new name, which is
     * worth far more than a default. Fabric API's {@code playS2C()} is
     * {@code clientboundPlay()} on 26.x, and old mods register their packets
     * through it from their initialiser.
     */
    public MissingMemberTransformer withEquivalents(studios.milkdromeda.octo.compat.api.Equivalents table) {
        this.equivalents = table == null ? studios.milkdromeda.octo.compat.api.Equivalents.EMPTY : table;
        return this;
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
                && !isAlsoCovered(className) && !isForPatchedGame(context)) {
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
        if (!(translatedModsOnly ? mentionsGame : MENTIONS_LOADER_API).matches(bytes)) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        Rewriter rewriter = new Rewriter(writer, context, className);
        rewriter.declare(reader);
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


    /**
     * A Forge or NeoForge mod built for this very version: it was compiled
     * against the loader's patched game, and the members those patches add
     * are missing from the game Octo runs just as surely as the members an
     * older release had. Fabric's are not: they run on the game as it is.
     */
    private static boolean isForPatchedGame(TransformContext context) {
        return context.mod() != null && !context.mod().isBuiltin()
                && context.mod().format().family() == studios.milkdromeda.octo.mod.ModFormat.Family.FORGE;
    }

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

    /**
     * A mod's own classes are never repaired: a call from one of them to another
     * that does not link is a bug in the mod, or a translation error, and either
     * should be seen rather than papered over.
     */
    private boolean isOwnClass(TransformContext context, String owner) {
        if (context.mod() == null) {
            return false;
        }

        return ownClasses.computeIfAbsent(context.mod(), mod -> {
            Set<String> names = new java.util.HashSet<>();
            mod.candidate().bytecode().classes().forEach(scanned -> names.add(scanned.internalName()));
            return names;
        }).contains(owner);
    }

    private final class Rewriter extends ClassVisitor {
        private final TransformContext context;
        private final String className;
        private int classVersion;
        private boolean classIsInterface;
        /** Methods to add for method references that pointed at something absent. */
        private final List<org.objectweb.asm.Handle> stubs = new java.util.ArrayList<>();
        private final Set<String> receiverStubs = new java.util.HashSet<>();
        /** Methods to add that build an object whose constructor is absent, as {@code owner, descriptor}. */
        private final List<String[]> factories = new java.util.ArrayList<>();
        /** Methods to add that reach a member across a static/instance change, for calls with arguments. */
        private final List<Bridge> bridges = new java.util.ArrayList<>();
        boolean changed;

        /** The methods this class declares itself, its superclass and its interfaces. */
        private final Set<String> declared = new java.util.HashSet<>();
        private String superName;
        private String[] interfaces = new String[0];

        Rewriter(ClassVisitor delegate, TransformContext context, String className) {
            super(Opcodes.ASM9, delegate);
            this.context = context;
            this.className = className;
        }

        void declare(ClassReader reader) {
            superName = reader.getSuperName();
            interfaces = reader.getInterfaces();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    declared.add(name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        /**
         * The game class a call on this mod's own class really reaches, when
         * the mod never declares the method and the game no longer has it.
         * Create calls {@code simpleCodec}, a static it inherited from
         * {@code BlockBehaviour}, as {@code SchematicTableBlock.simpleCodec};
         * 26.x dropped it, and since the call names Create's own class it went
         * past the bypass and failed the block's static initialiser.
         *
         * @return that game class, or {@code null} when the call is the mod's own business
         */
        /**
         * The shim for the member on {@code owner}, or on the nearest of its
         * game ancestors that has one: an instance method a shim answers on
         * {@code Block} is answered on every block class below it too.
         */
        private String shimUpward(String owner, String name, String descriptor, int opcode) {
            String shim = Shims.shimFor(owner, name, descriptor);

            if (shim != null || opcode == Opcodes.INVOKESTATIC) {
                return shim;
            }

            String ancestor = lookup.superName(owner);

            for (int depth = 0; shim == null && ancestor != null && depth < 16 && isGameSide(ancestor); depth++) {
                shim = Shims.shimFor(ancestor, name, descriptor);
                ancestor = lookup.superName(ancestor);
            }

            return shim;
        }

        private boolean isGameSide(String internalName) {
            return internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/");
        }

        private String inheritedGap(String owner, String name, String descriptor) {
            if (!owner.equals(className) || declared.contains(name + descriptor) || superName == null) {
                return null;
            }

            String ancestor = superName;

            // Up through the mods' classes — this mod's, or another's it
            // builds on: an add-on's block extending Create's — to the first
            // of the game's.
            for (int depth = 0; ancestor != null && depth < 16 && !isGameSide(ancestor)
                    && !ancestor.startsWith("java/"); depth++) {
                if (!Boolean.FALSE.equals(lookup.hasMethod(ancestor, name, descriptor))) {
                    return null;
                }

                ancestor = lookup.superName(ancestor);
            }

            // Only a gap in the game: the JDK's classes, and the libraries a mod
            // brings, are not this bypass's to second-guess.
            if (ancestor == null || !(ancestor.startsWith("net/minecraft/") || ancestor.startsWith("com/mojang/"))
                    || !lookup.guards(ancestor)) {
                return null;
            }

            // A default method from one of the class's interfaces answers it too:
            // Registrate's builders inherit setData that way.
            for (String iface : interfaces) {
                if (!Boolean.FALSE.equals(lookup.hasMethod(iface, name, descriptor))) {
                    return null;
                }
            }

            return Boolean.FALSE.equals(lookup.hasMethod(ancestor, name, descriptor)) ? ancestor : null;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            classVersion = version & 0xFFFF;
            classIsInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        /**
         * A method reference — {@code FriendlyByteBuf::writeBlockHitResult}
         * handed to a codec — names its target in a handle, not an instruction,
         * and when that target is gone the lambda fails to link the first time
         * it is created. The handle is pointed at a method added to this class
         * that takes the same arguments and returns a default.
         */
        private org.objectweb.asm.Handle repairHandle(org.objectweb.asm.Handle handle) {
            int tag = handle.getTag();

            if (tag != Opcodes.H_INVOKEVIRTUAL && tag != Opcodes.H_INVOKESTATIC && tag != Opcodes.H_INVOKEINTERFACE
                    && tag != Opcodes.H_NEWINVOKESPECIAL) {
                return handle;
            }

            String owner = handle.getOwner();

            if (owner.equals(className) || owner.charAt(0) == '[' || isOwnClass(context, owner)
                    || !lookup.guards(owner) || !Boolean.FALSE.equals(lookup.hasMethod(owner, handle.getName(),
                    handle.getDesc()))) {
                return handle;
            }

            // A method reference adapts its arguments and result the way the
            // functional interface needs, but only by widening: an overload
            // whose result needs a cast cannot stand in for the original here.
            String compatible = compatibleOverload(owner, handle.getName(), handle.getDesc(),
                    tag == Opcodes.H_INVOKESTATIC, false);

            if (compatible != null) {
                changed = true;
                record(context, owner, handle.getName() + handle.getDesc(), className,
                        "references to it use " + handle.getName() + compatible);
                return new org.objectweb.asm.Handle(tag, owner, handle.getName(), compatible, handle.isInterface());
            }

            if (tag == Opcodes.H_NEWINVOKESPECIAL) {
                // Vec3::new, with the constructor gone: a factory that builds a
                // Vec3 however this version allows.
                if (classVersion < Opcodes.V1_5 || (classIsInterface && classVersion < Opcodes.V1_8)) {
                    return handle;
                }

                String factory = "octo$new$" + factories.size();
                factories.add(new String[] { owner, handle.getDesc() });
                changed = true;
                record(context, owner, "<init>" + handle.getDesc(), className,
                        "references to it build through a constructor this version has");
                return new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, className, factory,
                        factoryDescriptor(owner, handle.getDesc()), classIsInterface);
            }

            for (String alias : equivalents.methodAliases(owner, handle.getName())) {
                if (!alias.equals(handle.getName())
                        && Boolean.TRUE.equals(lookup.hasMethod(owner, alias, handle.getDesc()))) {
                    changed = true;
                    record(context, owner, handle.getName() + handle.getDesc(), className, "it is called " + alias
                            + " now");
                    return new org.objectweb.asm.Handle(tag, owner, alias, handle.getDesc(), handle.isInterface());
                }
            }

            String descriptor = tag == Opcodes.H_INVOKESTATIC ? handle.getDesc()
                    : "(L" + owner + ";" + handle.getDesc().substring(1);
            org.objectweb.asm.Handle stub = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, className,
                    "octo$missing$" + stubs.size(), descriptor, classIsInterface);
            stubs.add(stub);
            changed = true;

            // A builder's setter, passed as Properties::noOcclusion: handing
            // back the builder keeps the chain going, as a direct call does.
            if (tag != Opcodes.H_INVOKESTATIC && returnsReceiver(owner, handle.getName(), handle.getDesc())) {
                receiverStubs.add(stub.getName());
                record(context, owner, handle.getName() + handle.getDesc(), className,
                        "references to it hand back their receiver");
            } else {
                record(context, owner, handle.getName() + handle.getDesc(), className,
                        "references to it return a default");
            }

            return stub;
        }

        @Override
        public void visitEnd() {
            for (org.objectweb.asm.Handle stub : stubs) {
                // Public on an interface, where a private static needs Java 9.
                int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC
                        | (classIsInterface ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE);
                MethodVisitor method = super.visitMethod(access, stub.getName(), stub.getDesc(), null, null);
                method.visitCode();
                Type returnType = Type.getReturnType(stub.getDesc());

                if (receiverStubs.contains(stub.getName())) {
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitInsn(Opcodes.ARETURN);
                } else if (returnType.getSort() == Type.OBJECT && Nothing.pushLive(method, returnType, className)) {
                    method.visitInsn(Opcodes.ARETURN);
                } else {
                    Nothing.push(method, returnType);
                    method.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
                }

                method.visitMaxs(0, 0);
                method.visitEnd();
            }

            for (int i = 0; i < factories.size(); i++) {
                writeFactory("octo$new$" + i, factories.get(i)[0], factories.get(i)[1]);
            }

            for (Bridge bridge : bridges) {
                writeBridge(bridge);
            }

            super.visitEnd();
        }

        /**
         * Adds a bridge for a call whose target moved between static and
         * instance, and returns its name. The arguments are on the stack above
         * where the receiver goes (or above a receiver that is no longer
         * wanted), and a method of the caller's own is the one place they can
         * be rearranged without knowing anything else about the method.
         */
        private String bridge(String owner, String method, String descriptor, String singleton,
                boolean ownerIsInterface) {
            String bridgeDescriptor = singleton != null ? descriptor : "(L" + owner + ";" + descriptor.substring(1);

            for (Bridge existing : bridges) {
                if (existing.owner().equals(owner) && existing.method().equals(method)
                        && existing.descriptor().equals(bridgeDescriptor)) {
                    return existing.name();
                }
            }

            Bridge bridge = new Bridge("octo$bridge$" + bridges.size(), bridgeDescriptor, owner, method, descriptor,
                    singleton, ownerIsInterface);
            bridges.add(bridge);
            return bridge.name();
        }

        private void writeBridge(Bridge bridge) {
            int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC
                    | (classIsInterface ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE);
            MethodVisitor method = super.visitMethod(access, bridge.name(), bridge.descriptor(), null, null);
            method.visitCode();
            int slot = 0;

            if (bridge.singleton() != null) {
                method.visitMethodInsn(Opcodes.INVOKESTATIC, bridge.owner(), bridge.singleton(),
                        "()L" + bridge.owner() + ";", bridge.ownerIsInterface());
            } else {
                // The receiver the caller still passes, which the static method does not want.
                slot = 1;
            }

            for (Type argument : Type.getArgumentTypes(bridge.methodDescriptor())) {
                method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
                slot += argument.getSize();
            }

            int opcode = bridge.singleton() == null ? Opcodes.INVOKESTATIC
                    : bridge.ownerIsInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            method.visitMethodInsn(opcode, bridge.owner(), bridge.method(), bridge.methodDescriptor(),
                    bridge.ownerIsInterface());
            method.visitInsn(Type.getReturnType(bridge.methodDescriptor()).getOpcode(Opcodes.IRETURN));
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        /**
         * A static method taking a missing constructor's arguments and returning
         * what {@link GapBridge} could build from them.
         */
        private void writeFactory(String name, String owner, String constructor) {
            int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC
                    | (classIsInterface ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE);
            Type[] arguments = Type.getArgumentTypes(constructor);
            MethodVisitor method = super.visitMethod(access, name, factoryDescriptor(owner, constructor), null, null);
            method.visitCode();
            method.visitLdcInsn(Type.getObjectType(owner));
            pushInt(method, arguments.length);
            method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            int slot = 0;

            for (int i = 0; i < arguments.length; i++) {
                method.visitInsn(Opcodes.DUP);
                pushInt(method, i);
                method.visitVarInsn(arguments[i].getOpcode(Opcodes.ILOAD), slot);
                box(method, arguments[i]);
                method.visitInsn(Opcodes.AASTORE);
                slot += arguments[i].getSize();
            }

            method.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/transform/GapBridge", "construct",
                    "(Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            method.visitTypeInsn(Opcodes.CHECKCAST, owner);
            method.visitInsn(Opcodes.ARETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor, String signature,
                String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, methodName, methodDescriptor, signature, exceptions);

            return delegate == null ? null : new MethodVisitor(Opcodes.ASM9, delegate) {
                /** The objects made by {@code NEW} whose constructor has not been called yet, innermost first. */
                private final java.util.ArrayDeque<PendingNew> pending = new java.util.ArrayDeque<>();
                /** The {@code NEW} just visited, until the next instruction says whether it was duplicated. */
                private PendingNew justMade;
                /** A local slot past every one the method has used so far, for values held a moment. */
                private int nextFree = (Type.getArgumentsAndReturnSizes(methodDescriptor) >> 2)
                        - ((access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    justMade = null;
                    super.visitTypeInsn(opcode, type);

                    if (opcode == Opcodes.NEW) {
                        justMade = new PendingNew(type);
                        pending.push(justMade);
                    }
                }

                @Override
                public void visitInsn(int opcode) {
                    if (justMade != null && opcode == Opcodes.DUP) {
                        justMade.duplicated = true;
                    }

                    justMade = null;
                    super.visitInsn(opcode);
                }

                @Override
                public void visitVarInsn(int opcode, int varIndex) {
                    justMade = null;
                    boolean wide = opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD || opcode == Opcodes.LSTORE
                            || opcode == Opcodes.DSTORE;
                    nextFree = Math.max(nextFree, varIndex + (wide ? 2 : 1));
                    super.visitVarInsn(opcode, varIndex);
                }

                @Override
                public void visitIincInsn(int varIndex, int increment) {
                    justMade = null;
                    nextFree = Math.max(nextFree, varIndex + 1);
                    super.visitIincInsn(varIndex, increment);
                }

                @Override
                public void visitIntInsn(int opcode, int operand) {
                    justMade = null;
                    super.visitIntInsn(opcode, operand);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    justMade = null;
                    super.visitLdcInsn(value);
                }

                @Override
                public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                    justMade = null;
                    super.visitJumpInsn(opcode, label);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                        boolean isInterface) {
                    justMade = null;

                    // MethodHandle.invoke and VarHandle.get take whatever
                    // descriptor the call site gives them: never in any
                    // index, never missing. Defaulted, Steam 'n' Rails lost
                    // its Registrate to a null and every block it made.
                    if (owner.equals("java/lang/invoke/MethodHandle") || owner.equals("java/lang/invoke/VarHandle")) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        return;
                    }

                    if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>")) {
                        constructor(owner, descriptor);
                        return;
                    }

                    if (opcode != Opcodes.INVOKESPECIAL && name.charAt(0) != '<') {
                        String inherited = inheritedGap(owner, name, descriptor);

                        if (inherited != null) {
                            owner = inherited;
                        }
                    }

                    // A super call to what nothing above the class has any
                    // more: Create's block entities save through
                    // super.saveAdditional(tag, registries), which no class up
                    // to the game's BlockEntity declares since 1.21.6. The
                    // game's part is done before the old method is reached
                    // (ValueIoBridge), so the call is skipped.
                    if (opcode == Opcodes.INVOKESPECIAL && name.charAt(0) != '<' && !owner.equals(className)
                            && lookup.guards(owner) && Boolean.FALSE.equals(lookup.hasMethod(owner, name, descriptor))) {
                        discard(Type.getArgumentTypes(descriptor), true);
                        Type returned = Type.getReturnType(descriptor);

                        if (returned.getSort() != Type.VOID) {
                            pushResult(returned, owner);
                        }

                        changed = true;
                        record(context, owner, name + descriptor, className,
                                "nothing above the class has it any more, so the super call is skipped");
                        return;
                    }

                    // Any other super call is part of the class's own shape,
                    // and is never touched.
                    if (opcode == Opcodes.INVOKESPECIAL || name.charAt(0) == '<' || owner.equals(className)
                            || owner.charAt(0) == '[' || isOwnClass(context, owner) || !lookup.guards(owner)) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        return;
                    }

                    Boolean exists = lookup.hasMethod(owner, name, descriptor);

                    if (exists == null && lookup.isAbsent(owner, context)) {
                        exists = Boolean.FALSE;
                    }

                    if (exists == null) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        return;
                    }

                    if (exists && inaccessible(owner, name, descriptor)) {
                        // There, but no longer callable from here: as good as gone.
                        exists = Boolean.FALSE;
                    }

                    if (exists) {
                        repairShape(opcode, owner, name, descriptor, isInterface);
                        return;
                    }

                    for (String alias : equivalents.methodAliases(owner, name)) {
                        if (!alias.equals(name) && Boolean.TRUE.equals(lookup.hasMethod(owner, alias, descriptor))) {
                            super.visitMethodInsn(opcode, owner, alias, descriptor, isInterface);
                            changed = true;
                            record(context, owner, name + descriptor, className, "it is called " + alias + " now");
                            return;
                        }
                    }

                    // The same method with wider parameters, or a narrower
                    // result: Vec3's methods taking Vector3fc where they took
                    // Vector3f are the kind of change this is.
                    List<String> names = new java.util.ArrayList<>();
                    names.add(name);
                    names.addAll(equivalents.methodAliases(owner, name));

                    for (String candidateName : names) {
                        String compatible = compatibleOverload(owner, candidateName, descriptor,
                                opcode == Opcodes.INVOKESTATIC, true);

                        if (compatible != null) {
                            repairShape(opcode, owner, candidateName, compatible, isInterface);
                            adaptResult(Type.getReturnType(compatible), Type.getReturnType(descriptor));
                            changed = true;
                            record(context, owner, name + descriptor, className,
                                    "it is " + candidateName + compatible + " now");
                            return;
                        }
                    }

                    // A static factory moved to the class's plural holder, as
                    // 26.x moved RenderType.entitySolid(...) to RenderTypes and
                    // IntProvider.codec(...) to IntProviders.
                    if (opcode == Opcodes.INVOKESTATIC) {
                        String holder = owner + "s";
                        String moved = lookup.guards(holder)
                                ? compatibleOverload(holder, name, descriptor, true, true) : null;

                        if (moved == null && lookup.guards(holder)
                                && Boolean.TRUE.equals(lookup.isStatic(holder, name, descriptor))) {
                            moved = descriptor;
                        }

                        if (moved != null && !inaccessible(holder, name, moved)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, holder, name, moved,
                                    Boolean.TRUE.equals(lookup.isInterface(holder)));
                            adaptResult(Type.getReturnType(moved), Type.getReturnType(descriptor));
                            changed = true;
                            record(context, owner, name + descriptor, className, "it is " + holder.replace('/', '.')
                                    + "." + name + " now");
                            return;
                        }
                    }

                    if (followWithoutPlace(opcode, owner, name, descriptor, isInterface)) {
                        return;
                    }

                    if (followNewShape(opcode, owner, name, descriptor, isInterface)) {
                        return;
                    }

                    String shim = classVersion >= Opcodes.V1_5 ? shimUpward(owner, name, descriptor, opcode) : null;

                    if (shim != null) {
                        // Removed, but the old behaviour is a line of the new
                        // API: DataResult.get() is result() or error(), wrapped.
                        boolean isStatic = opcode == Opcodes.INVOKESTATIC;

                        if (isStatic) {
                            delegate.visitLdcInsn(Type.getObjectType(owner));
                        }

                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, Shims.owner(), shim,
                                Shims.descriptorFor(descriptor, isStatic), false);
                        Type returned = Type.getReturnType(descriptor);

                        if (returned.getSort() == Type.OBJECT || returned.getSort() == Type.ARRAY) {
                            delegate.visitTypeInsn(Opcodes.CHECKCAST, returned.getSort() == Type.ARRAY
                                    ? returned.getDescriptor() : returned.getInternalName());
                        }

                        changed = true;
                        record(context, owner, name + descriptor, className,
                                "it is rebuilt from what this version's API has");
                        return;
                    }

                    Type returnType = Type.getReturnType(descriptor);
                    boolean hasReceiver = opcode != Opcodes.INVOKESTATIC;

                    // The game's block renderer family, where this version
                    // still has the class but not the old method: Create
                    // tesselates every moving part through ModelBlockRenderer's
                    // old tesselateBlock. Answered with this version's models,
                    // as the classes added back whole are (RestoredBridges).
                    if (classVersion >= Opcodes.V1_5 && RestoredBridges.bridges(owner)) {
                        bridgedCall(owner, name, descriptor, hasReceiver);
                        changed = true;
                        record(context, owner, name + descriptor, className,
                                "it is drawn with this version's models");
                        return;
                    }

                    if (hasReceiver && returnsReceiver(owner, name, descriptor)) {
                        // A builder method this version does not have: drop the
                        // arguments and hand the builder back, so the chain goes on.
                        discard(Type.getArgumentTypes(descriptor), false);
                        changed = true;
                        record(context, owner, name + descriptor, className, "the call hands back its receiver");
                        return;
                    }

                    discard(Type.getArgumentTypes(descriptor), hasReceiver);
                    pushResult(returnType, owner);
                    changed = true;
                    record(context, owner, name + descriptor, className, "calls to it return a default");
                }

                /**
                 * A constructor call, which unlike a method cannot be skipped:
                 * the object it makes is what the caller goes on to use.
                 *
                 * <p>A constructor whose parameters the arguments still fit is
                 * called instead. Failing that, the object is built through
                 * {@link GapBridge} from whatever this version's constructors
                 * accept, and the uninitialised object {@code NEW} made — which
                 * now never will be initialised — is dropped from under it.
                 */
                private void constructor(String owner, String descriptor) {
                    PendingNew made = pending.peek();

                    // No NEW waiting for this: a super() or this() call, which is
                    // the class's own shape — unless the superclass is the
                    // game's and no longer has the constructor. A NEW of some
                    // other type: code this cannot follow, left exactly as it is.
                    if (made == null && methodName.equals("<init>") && owner.equals(superName)
                            && superConstructorGap(owner, descriptor)) {
                        return;
                    }

                    if (made == null || !made.type.equals(owner)) {
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", descriptor, false);
                        return;
                    }

                    pending.pop();

                    if (owner.equals(className) || isOwnClass(context, owner) || !lookup.guards(owner)) {
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", descriptor, false);
                        return;
                    }

                    Boolean exists = lookup.hasMethod(owner, "<init>", descriptor);
                    boolean blocked = Boolean.TRUE.equals(exists) && inaccessible(owner, "<init>", descriptor);

                    if (!blocked && !Boolean.FALSE.equals(exists)) {
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", descriptor, false);
                        return;
                    }

                    String compatible = blocked ? null : compatibleOverload(owner, "<init>", descriptor, false, false);

                    if (compatible != null) {
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", compatible, false);
                        changed = true;
                        record(context, owner, "<init>" + descriptor, className, "it takes " + compatible + " now");
                        return;
                    }

                    // A constructor whose old arguments are a few lines of the new
                    // API: the old BakedQuad's vertex data, made into 26.x's record.
                    String constructorShim = blocked || classVersion < Opcodes.V1_5 ? null
                            : Shims.shimFor(owner, "<init>", descriptor);

                    if (constructorShim != null) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, Shims.owner(), constructorShim,
                                Shims.constructorDescriptor(descriptor), false);
                        super.visitTypeInsn(Opcodes.CHECKCAST, owner);
                        dropUninitialised(made);
                        changed = true;
                        record(context, owner, "<init>" + descriptor, className,
                                "it is rebuilt from what this version's API has");
                        return;
                    }

                    // A constructor that is private now, or gone — ResourceLocation's,
                    // which every 1.20 mod calls — is reached the way the class
                    // wants to be built instead: Identifier.fromNamespaceAndPath
                    // for (namespace, path), Identifier.parse for "namespace:path".
                    String factoryMethod = staticFactory(owner, descriptor);

                    if (factoryMethod != null) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, factoryMethod,
                                factoryDescriptor(owner, descriptor), Boolean.TRUE.equals(lookup.isInterface(owner)));
                        dropUninitialised(made);
                        changed = true;
                        record(context, owner, "<init>" + descriptor, className,
                                (blocked ? "it is private now" : "it is gone") + ", so " + factoryMethod
                                        + " builds it");
                        return;
                    }

                    // A class constant needs Java 5, a static method on an
                    // interface Java 8.
                    if (classVersion < Opcodes.V1_5 || (classIsInterface && classVersion < Opcodes.V1_8)) {
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", descriptor, false);
                        return;
                    }

                    String factory = "octo$new$" + factories.size();
                    factories.add(new String[] { owner, descriptor });
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, className, factory,
                            factoryDescriptor(owner, descriptor), classIsInterface);
                    dropUninitialised(made);
                    changed = true;
                    record(context, owner, "<init>" + descriptor, className,
                            "it is built through a constructor this version has");
                }

                /**
                 * A super() call whose arguments the superclass now takes in
                 * another order: 26.x's {@code StandingAndWallBlockItem}
                 * takes its direction before the item properties, and Sign
                 * items, added back from the release before, pass them the
                 * old way round. Each argument is held in a spare local and
                 * handed over where the constructor this version has wants it.
                 *
                 * @return whether the call was written
                 */
                private boolean reorderedSuperCall(String owner, String descriptor) {
                    Type[] arguments = Type.getArgumentTypes(descriptor);

                    if (arguments.length < 2) {
                        return false;
                    }

                    for (String candidate : lookup.overloads(owner, "<init>")) {
                        Type[] parameters = Type.getArgumentTypes(candidate);
                        int[] from = parameters.length == arguments.length ? permutation(arguments, parameters) : null;
                        Integer access = from == null ? null : lookup.methodAccess(owner, "<init>", candidate);

                        if (access == null || (access & Opcodes.ACC_PRIVATE) != 0) {
                            continue;
                        }

                        int[] slots = new int[arguments.length];
                        int slot = nextFree;

                        for (int i = 0; i < arguments.length; i++) {
                            slots[i] = slot;
                            slot += arguments[i].getSize();
                        }

                        for (int i = arguments.length - 1; i >= 0; i--) {
                            super.visitVarInsn(arguments[i].getOpcode(Opcodes.ISTORE), slots[i]);
                        }

                        for (int index : from) {
                            super.visitVarInsn(arguments[index].getOpcode(Opcodes.ILOAD), slots[index]);
                        }

                        nextFree = slot;
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", candidate, false);
                        changed = true;
                        record(context, owner, "<init>" + descriptor, className,
                                "it takes the same arguments in another order now, <init>" + candidate);
                        return true;
                    }

                    return false;
                }

                /**
                 * @return for each parameter, which argument it takes, each
                 *         argument once; {@code null} unless every one fits
                 */
                private int[] permutation(Type[] arguments, Type[] parameters) {
                    int[] from = new int[parameters.length];
                    boolean[] taken = new boolean[arguments.length];

                    for (int i = 0; i < parameters.length; i++) {
                        from[i] = -1;

                        // Exact types first, so two blocks keep their order.
                        for (int j = 0; j < arguments.length && from[i] < 0; j++) {
                            if (!taken[j] && parameters[i].equals(arguments[j])) {
                                from[i] = j;
                                taken[j] = true;
                            }
                        }

                        for (int j = 0; j < arguments.length && from[i] < 0; j++) {
                            if (!taken[j] && accepts(parameters[i], arguments[j])) {
                                from[i] = j;
                                taken[j] = true;
                            }
                        }

                        if (from[i] < 0) {
                            return null;
                        }
                    }

                    return from;
                }

                /**
                 * A super() call to a constructor the game's superclass no
                 * longer has: Create's {@code ItemCopyingRecipe} calls
                 * {@code CustomRecipe(CraftingBookCategory)}, and 26.x's
                 * {@code CustomRecipe} takes nothing. The constructor the
                 * arguments still fit is called, or else the superclass's
                 * simplest, with defaults for what it takes.
                 *
                 * @return whether the call was written
                 */
                private boolean superConstructorGap(String owner, String descriptor) {
                    if (isOwnClass(context, owner) || !lookup.guards(owner)) {
                        return false;
                    }

                    Boolean exists = lookup.hasMethod(owner, "<init>", descriptor);

                    if (!Boolean.FALSE.equals(exists)) {
                        return false;
                    }

                    String compatible = compatibleOverload(owner, "<init>", descriptor, false, false);

                    if (compatible != null) {
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", compatible, false);
                        changed = true;
                        record(context, owner, "<init>" + descriptor, className, "it takes " + compatible + " now");
                        return true;
                    }

                    if (reorderedSuperCall(owner, descriptor)) {
                        return true;
                    }

                    String simplest = null;

                    for (String candidate : lookup.overloads(owner, "<init>")) {
                        Integer access = lookup.methodAccess(owner, "<init>", candidate);

                        if (access == null || (access & Opcodes.ACC_PRIVATE) != 0
                                || (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0
                                        && !packageOf(owner).equals(packageOf(className))) {
                            continue;
                        }

                        if (simplest == null
                                || Type.getArgumentTypes(candidate).length < Type.getArgumentTypes(simplest).length) {
                            simplest = candidate;
                        }
                    }

                    if (simplest == null) {
                        return false;
                    }

                    // The arguments go; under them, the object being built stays.
                    discard(Type.getArgumentTypes(descriptor), false);

                    for (Type parameter : Type.getArgumentTypes(simplest)) {
                        pushSample(parameter, owner);
                    }

                    super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", simplest, false);
                    changed = true;
                    record(context, owner, "<init>" + descriptor, className,
                            "it is gone, so the superclass is built through <init>" + simplest);
                    return true;
                }

                /**
                 * A getter that changed the way 1.21.5 changed every getter on
                 * {@code CompoundTag}: what used to answer a default when the
                 * value was absent answers an {@code Optional} now, or has
                 * grown an {@code ...Or(key, default)} or {@code ...OrEmpty}
                 * form, or lost the type id it used to take.
                 *
                 * <p>A mod reading its saved data through the old getter is
                 * given what the old getter gave — the value, or the same
                 * default it used to fall back on. Answering a bare default
                 * instead would read every saved value as zero, which is how a
                 * world loses its data.
                 *
                 * @return whether the call was repaired
                 */
                /**
                 * The same method, no longer told where: 1.21.2 took the level
                 * and position from what a block state works out on its own —
                 * {@code isSolidRender(level, pos)} is {@code isSolidRender()},
                 * {@code getFaceOcclusionShape(level, pos, side)} is {@code
                 * getFaceOcclusionShape(side)}. The arguments that said where
                 * are dropped, and the rest passed on in order.
                 */
                private boolean followWithoutPlace(int opcode, String owner, String name, String descriptor,
                        boolean isInterface) {
                    if (name.charAt(0) == '<' || opcode == Opcodes.INVOKESPECIAL) {
                        return false;
                    }

                    Type[] arguments = Type.getArgumentTypes(descriptor);
                    Type wanted = Type.getReturnType(descriptor);
                    boolean wantsStatic = opcode == Opcodes.INVOKESTATIC;

                    for (String candidate : lookup.overloads(owner, name)) {
                        Type[] parameters = Type.getArgumentTypes(candidate);

                        if (parameters.length >= arguments.length
                                || !usableResult(Type.getReturnType(candidate), wanted, false)
                                || !Boolean.valueOf(wantsStatic).equals(lookup.isStatic(owner, name, candidate))) {
                            continue;
                        }

                        boolean[] kept = new boolean[arguments.length];
                        int next = 0;
                        boolean fits = true;

                        for (int i = 0; i < arguments.length && fits; i++) {
                            if (next < parameters.length && accepts(parameters[next], arguments[i])) {
                                kept[i] = true;
                                next++;
                            } else {
                                fits = isPlace(arguments[i]);
                            }
                        }

                        if (!fits || next != parameters.length) {
                            continue;
                        }

                        int[] slots = new int[arguments.length];
                        int slot = nextFree;

                        for (int i = 0; i < arguments.length; i++) {
                            slots[i] = slot;
                            slot += arguments[i].getSize();
                        }

                        for (int i = arguments.length - 1; i >= 0; i--) {
                            super.visitVarInsn(arguments[i].getOpcode(Opcodes.ISTORE), slots[i]);
                        }

                        nextFree = slot;

                        for (int i = 0; i < arguments.length; i++) {
                            if (kept[i]) {
                                super.visitVarInsn(arguments[i].getOpcode(Opcodes.ILOAD), slots[i]);
                            }
                        }

                        super.visitMethodInsn(opcode, owner, name, candidate, isInterface);

                        if (wanted.getSort() == Type.VOID && Type.getReturnType(candidate).getSort() != Type.VOID) {
                            pop(Type.getReturnType(candidate));
                        }

                        return followed(owner, name, descriptor, name + candidate + ", without where");
                    }

                    return false;
                }

                /** A level, or something read like one, or a position in it. */
                private boolean isPlace(Type argument) {
                    if (argument.getSort() != Type.OBJECT) {
                        return false;
                    }

                    String type = argument.getInternalName();
                    return type.equals("net/minecraft/core/BlockPos")
                            || type.equals("net/minecraft/world/level/BlockGetter")
                            || lookup.isAssignable(type, "net/minecraft/world/level/BlockGetter");
                }

                private boolean followNewShape(int opcode, String owner, String name, String descriptor,
                        boolean isInterface) {
                    Type[] arguments = Type.getArgumentTypes(descriptor);
                    Type wanted = Type.getReturnType(descriptor);
                    boolean wantsStatic = opcode == Opcodes.INVOKESTATIC;

                    if (wanted.getSort() == Type.VOID) {
                        return false;
                    }

                    for (int keep = arguments.length; keep >= 0; keep--) {
                        String kept = argumentList(arguments, keep);
                        String orEmpty = "(" + kept + ")" + wanted.getDescriptor();
                        String or = "(" + kept + wanted.getDescriptor() + ")" + wanted.getDescriptor();
                        String optional = "(" + kept + ")Ljava/util/Optional;";

                        if (has(owner, name + "OrEmpty", orEmpty, wantsStatic)) {
                            dropFrom(arguments, keep);
                            super.visitMethodInsn(opcode, owner, name + "OrEmpty", orEmpty, isInterface);
                            return followed(owner, name, descriptor, name + "OrEmpty" + orEmpty);
                        }

                        if (has(owner, name + "Or", or, wantsStatic)) {
                            dropFrom(arguments, keep);
                            pushFallback(wanted);
                            super.visitMethodInsn(opcode, owner, name + "Or", or, isInterface);
                            return followed(owner, name, descriptor, name + "Or" + or);
                        }

                        if (!wanted.getDescriptor().equals("Ljava/util/Optional;")
                                && has(owner, name, optional, wantsStatic)) {
                            dropFrom(arguments, keep);
                            super.visitMethodInsn(opcode, owner, name, optional, isInterface);
                            unwrap(wanted);
                            return followed(owner, name, descriptor, name + optional + ", unwrapped");
                        }

                        if (keep < arguments.length && has(owner, name, orEmpty, wantsStatic)) {
                            dropFrom(arguments, keep);
                            super.visitMethodInsn(opcode, owner, name, orEmpty, isInterface);
                            return followed(owner, name, descriptor, name + orEmpty);
                        }

                        // Only trailing primitives are let go of: a type id, a
                        // flag. An object argument is the call's subject.
                        if (keep == 0 || arguments[keep - 1].getSort() == Type.OBJECT
                                || arguments[keep - 1].getSort() == Type.ARRAY) {
                            break;
                        }
                    }

                    return false;
                }

                private boolean has(String owner, String name, String descriptor, boolean wantsStatic) {
                    return Boolean.TRUE.equals(lookup.hasMethod(owner, name, descriptor))
                            && Boolean.valueOf(wantsStatic).equals(lookup.isStatic(owner, name, descriptor));
                }

                private boolean followed(String owner, String name, String descriptor, String now) {
                    changed = true;
                    record(context, owner, name + descriptor, className, "it is " + now + " now");
                    return true;
                }

                /** Pops the arguments from {@code keep} on, which are the ones on top of the stack. */
                private void dropFrom(Type[] arguments, int keep) {
                    for (int i = arguments.length - 1; i >= keep; i--) {
                        pop(arguments[i]);
                    }
                }

                /** {@code Optional.orElse(theOldDefault)}, then the type the caller expects. */
                private void unwrap(Type wanted) {
                    pushFallback(wanted);
                    box(delegate, wanted);
                    delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse",
                            "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    String wrapper = wrapperOf(wanted);

                    if (wrapper == null) {
                        delegate.visitTypeInsn(Opcodes.CHECKCAST, wanted.getSort() == Type.ARRAY
                                ? wanted.getDescriptor() : wanted.getInternalName());
                    } else {
                        delegate.visitTypeInsn(Opcodes.CHECKCAST, wrapper);
                        delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapper, wanted.getClassName() + "Value",
                                "()" + wanted.getDescriptor(), false);
                    }
                }

                /**
                 * What the old getters fell back on when a value was absent:
                 * zero, false, an empty string, an empty array or collection.
                 */
                private void pushFallback(Type type) {
                    if (type.getDescriptor().equals("Ljava/lang/String;")) {
                        delegate.visitLdcInsn("");
                    } else {
                        Nothing.push(delegate, type);
                    }
                }

                /**
                 * Removes what {@code NEW} left on the stack, now that the
                 * object came from somewhere else and sits above it.
                 */
                private void dropUninitialised(PendingNew made) {
                    if (made.duplicated) {
                        // uninitialised, uninitialised, built -> built
                        super.visitInsn(Opcodes.DUP_X2);
                        super.visitInsn(Opcodes.POP);
                        super.visitInsn(Opcodes.POP2);
                    } else {
                        // uninitialised, built -> nothing; the caller discarded it
                        super.visitInsn(Opcodes.POP);
                        super.visitInsn(Opcodes.POP);
                    }
                }

                /** Makes a call's result what the caller expects of it. */
                private void adaptResult(Type returned, Type wanted) {
                    if (returned.equals(wanted)) {
                        return;
                    }

                    if (wanted.getSort() == Type.VOID) {
                        pop(returned);
                        return;
                    }

                    if (returned.getSort() != Type.OBJECT || wanted.getSort() != Type.OBJECT
                            || !lookup.isAssignable(returned.getInternalName(), wanted.getInternalName())) {
                        super.visitTypeInsn(Opcodes.CHECKCAST, wanted.getSort() == Type.ARRAY
                                ? wanted.getDescriptor() : wanted.getInternalName());
                    }
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

                    // With arguments on the stack above it, the receiver cannot be
                    // added or dropped in place; a bridge method does it instead.
                    if (isStatic != null && isStatic != wantsStatic && !zeroArguments
                            && !(classIsInterface && classVersion < Opcodes.V1_8)) {
                        boolean itf = Boolean.TRUE.equals(ownerIsInterface);
                        String singleton = isStatic ? null : singletonAccessor(owner);

                        if (isStatic || singleton != null) {
                            String bridge = bridge(owner, name, descriptor, singleton, itf);
                            String bridgeDescriptor = isStatic ? "(L" + owner + ";" + descriptor.substring(1)
                                    : descriptor;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, className, bridge, bridgeDescriptor,
                                    classIsInterface);
                            changed = true;
                            record(context, owner, name + descriptor, className, isStatic ? "it is static now"
                                    : "it is reached through " + singleton + "() now");
                            return;
                        }
                    }

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
                public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrap,
                        Object... arguments) {
                    justMade = null;
                    Object[] repaired = arguments.clone();

                    for (int i = 0; i < repaired.length; i++) {
                        if (repaired[i] instanceof org.objectweb.asm.Handle handle) {
                            repaired[i] = repairHandle(handle);
                        }
                    }

                    super.visitInvokeDynamicInsn(name, descriptor, bootstrap, repaired);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    justMade = null;

                    // A field with a shim, on a class that is not here at all:
                    // the checks below only look at classes the game has, and
                    // NeoForge 26 dropped Capabilities.ItemHandler and its kin
                    // whole, so a mod reading one got a stand-in's null.
                    if (opcode == Opcodes.GETSTATIC && classVersion >= Opcodes.V1_5 && !owner.equals(className)
                            && !isOwnClass(context, owner) && !context.classExists().test(owner)
                            && emitFieldShim(owner, name, descriptor)) {
                        record(context, owner, name, className, "it is rebuilt from what this version's API has");
                        return;
                    }

                    // There, but closed to the mod: Level.isClientSide was a
                    // public field until 26.x made it private behind
                    // isClientSide(), and every block Create has that asks it
                    // was an IllegalAccessError that stopped the server.
                    if (!owner.equals(className) && !isOwnClass(context, owner) && lookup.guards(owner)
                            && Boolean.TRUE.equals(lookup.hasField(owner, name, descriptor))
                            && fieldInaccessible(owner, name, descriptor)) {
                        String how = reachClosedField(opcode, owner, name, descriptor);
                        changed = true;
                        record(context, owner, name, className, how);
                        return;
                    }

                    if (owner.equals(className) || isOwnClass(context, owner) || !lookup.guards(owner)
                            || !(Boolean.FALSE.equals(lookup.hasField(owner, name, descriptor))
                                    || lookup.isAbsent(owner, context))) {
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                        return;
                    }

                    for (String alias : equivalents.fieldAliases(owner, name)) {
                        if (!alias.equals(name) && Boolean.TRUE.equals(lookup.hasField(owner, alias, descriptor))) {
                            super.visitFieldInsn(opcode, owner, alias, descriptor);
                            changed = true;
                            record(context, owner, name, className, "it is called " + alias + " now");
                            return;
                        }
                    }

                    // A constant moved to the class's plural holder: IntProvider.CODEC
                    // is IntProviders.CODEC since 26.x.
                    if (opcode == Opcodes.GETSTATIC && lookup.guards(owner + "s")
                            && Boolean.TRUE.equals(lookup.hasField(owner + "s", name, descriptor))
                            && !fieldInaccessible(owner + "s", name, descriptor)) {
                        super.visitFieldInsn(opcode, owner + "s", name, descriptor);
                        changed = true;
                        record(context, owner, name, className, "it is " + (owner + "s").replace('/', '.') + "."
                                + name + " now");
                        return;
                    }

                    Type type = Type.getType(descriptor);

                    // A registry this version dropped: Create's armour materials
                    // are put in a DeferredRegister for Registries.ARMOR_MATERIAL,
                    // gone since 1.21.2, and NeoForge refuses a null key. A key
                    // is only a name, so the name it had stands in; nothing is
                    // ever registered under it, and the mod carries on.
                    if (opcode == Opcodes.GETSTATIC && owner.equals(REGISTRIES) && descriptor.equals(RESOURCE_KEY)) {
                        delegate.visitLdcInsn(name.toLowerCase(java.util.Locale.ROOT));
                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/resources/Identifier",
                                "withDefaultNamespace", "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;",
                                false);
                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/resources/ResourceKey",
                                "createRegistryKey",
                                "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/resources/ResourceKey;", false);
                        changed = true;
                        record(context, owner, name, className, "that registry is gone, so a key of its name stands in");
                        return;
                    }

                    // And a registry this version dropped, read from
                    // BuiltInRegistries: an empty one of its name stands in.
                    if (opcode == Opcodes.GETSTATIC && owner.equals(BUILT_IN_REGISTRIES) && descriptor.equals(REGISTRY)) {
                        delegate.visitLdcInsn(name.toLowerCase(java.util.Locale.ROOT));
                        delegate.visitLdcInsn(Type.getObjectType("net/minecraft/core/Registry"));
                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/bridge/forge/LiveStandIns",
                                "detachedRegistry", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
                        delegate.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/core/Registry");
                        changed = true;
                        record(context, owner, name, className, "that registry is gone, so an empty one of its name "
                                + "stands in");
                        return;
                    }

                    // A block or an item this version keeps among its weathering
                    // states: Blocks.COPPER_BLOCK, EXPOSED_COPPER and their waxed
                    // kin are one WeatheringCopperCollection since 26.x, and
                    // every block Create copies the properties of copper from
                    // was a null, and not registered.
                    if (opcode == Opcodes.GETSTATIC && type.getSort() == Type.OBJECT
                            && readFromCollection(owner, name, type)) {
                        changed = true;
                        record(context, owner, name, className, "it is one of a collection of weathering states now");
                        return;
                    }

                    if (opcode == Opcodes.GETSTATIC && classVersion >= Opcodes.V1_5
                            && emitFieldShim(owner, name, descriptor)) {
                        record(context, owner, name, className, "it is rebuilt from what this version's API has");
                        return;
                    }

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

                /** Reads a removed static field through its shim, if it has one. */
                private boolean emitFieldShim(String owner, String name, String descriptor) {
                    String shim = Shims.fieldShimFor(owner, name, descriptor);

                    if (shim == null) {
                        return false;
                    }

                    delegate.visitLdcInsn(Type.getObjectType(owner));
                    delegate.visitMethodInsn(Opcodes.INVOKESTATIC, Shims.owner(), shim,
                            "(Ljava/lang/Class;)Ljava/lang/Object;", false);
                    delegate.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(descriptor).getInternalName());
                    changed = true;
                    return true;
                }

                /**
                 * Reaches a field the mod may no longer touch: through the
                 * accessor the game gives it — {@code isClientSide()},
                 * {@code getFoo()}, {@code setFoo(value)} — and where there is
                 * none, by reflection.
                 *
                 * @return how it is reached now, for the report
                 */
                private String reachClosedField(int opcode, String owner, String name, String descriptor) {
                    boolean isStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
                    boolean read = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;
                    String capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    List<String> candidates = read ? List.of(name, "get" + capitalised, "is" + capitalised)
                            : List.of("set" + capitalised);
                    String accessorDescriptor = read ? "()" + descriptor : "(" + descriptor + ")V";
                    boolean ownerIsInterface = Boolean.TRUE.equals(lookup.isInterface(owner));

                    for (String accessor : candidates) {
                        Integer access = lookup.methodAccess(owner, accessor, accessorDescriptor);

                        if (access != null && (access & Opcodes.ACC_PUBLIC) != 0
                                && ((access & Opcodes.ACC_STATIC) != 0) == isStatic) {
                            delegate.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC
                                    : ownerIsInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                                    owner, accessor, accessorDescriptor, ownerIsInterface);
                            return "it is closed now, so " + accessor + accessorDescriptor + " is called";
                        }
                    }

                    Type type = Type.getType(descriptor);

                    if (read) {
                        if (isStatic) {
                            delegate.visitInsn(Opcodes.ACONST_NULL);
                        }

                        delegate.visitLdcInsn(Type.getObjectType(owner));
                        delegate.visitLdcInsn(name);
                        delegate.visitLdcInsn(descriptor);
                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, GAP_BRIDGE, "readField",
                                "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)"
                                        + "Ljava/lang/Object;", false);
                        String wrapper = wrapperOf(type);

                        if (wrapper == null) {
                            delegate.visitTypeInsn(Opcodes.CHECKCAST, type.getSort() == Type.ARRAY
                                    ? type.getDescriptor() : type.getInternalName());
                        } else {
                            delegate.visitTypeInsn(Opcodes.CHECKCAST, wrapper);
                            delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapper, type.getClassName() + "Value",
                                    "()" + descriptor, false);
                        }
                    } else {
                        box(delegate, type);

                        if (isStatic) {
                            delegate.visitInsn(Opcodes.ACONST_NULL);
                            delegate.visitInsn(Opcodes.SWAP);
                        }

                        delegate.visitLdcInsn(Type.getObjectType(owner));
                        delegate.visitLdcInsn(name);
                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, GAP_BRIDGE, "writeField",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)V", false);
                    }

                    return "it is closed now, so it is " + (read ? "read" : "written") + " by reflection";
                }

                private boolean readFromCollection(String owner, String name, Type type) {
                    return readWeathering(owner, name, type) || readColour(owner, name, type);
                }

                /**
                 * Reads {@code YELLOW_WOOL} as {@code WOOL.yellow()}, and
                 * {@code RED_SHULKER_BOX} as {@code DYED_SHULKER_BOX.red()}:
                 * 26.x keeps each dyed block's sixteen colours in one
                 * {@code ColorCollection}.
                 */
                private boolean readColour(String owner, String name, Type type) {
                    for (String colour : COLOURS) {
                        String prefix = colour.toUpperCase(java.util.Locale.ROOT) + "_";

                        if (!name.startsWith(prefix) || name.length() == prefix.length()) {
                            continue;
                        }

                        String rest = name.substring(prefix.length());
                        String collection = "L" + COLOUR_COLLECTION + ";";

                        for (String base : List.of(rest, "DYED_" + rest)) {
                            if (Boolean.TRUE.equals(lookup.hasField(owner, base, collection))) {
                                delegate.visitFieldInsn(Opcodes.GETSTATIC, owner, base, collection);
                                delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOUR_COLLECTION,
                                        camelCase(colour), "()Ljava/lang/Object;", false);
                                delegate.visitTypeInsn(Opcodes.CHECKCAST, type.getInternalName());
                                return true;
                            }
                        }

                        return false;
                    }

                    return false;
                }

                /**
                 * Reads {@code WAXED_EXPOSED_CUT_COPPER} as
                 * {@code CUT_COPPER.waxed().exposed()}, when the owner has
                 * such a collection; the unweathered block is just
                 * {@code COPPER}'s, which was {@code COPPER_BLOCK}.
                 */
                private boolean readWeathering(String owner, String name, Type type) {
                    String rest = name;
                    boolean waxed = rest.startsWith("WAXED_");
                    String state = "unaffected";

                    if (waxed) {
                        rest = rest.substring("WAXED_".length());
                    }

                    for (String weathered : WEATHER_STATES) {
                        String prefix = weathered.toUpperCase(java.util.Locale.ROOT) + "_";

                        if (rest.startsWith(prefix)) {
                            state = weathered;
                            rest = rest.substring(prefix.length());
                            break;
                        }
                    }

                    String collection = "L" + COPPER_COLLECTION + ";";

                    for (String base : rest.equals("COPPER") ? List.of("COPPER", "COPPER_BLOCK") : List.of(rest)) {
                        if (Boolean.TRUE.equals(lookup.hasField(owner, base, collection))) {
                            delegate.visitFieldInsn(Opcodes.GETSTATIC, owner, base, collection);
                            delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COPPER_COLLECTION,
                                    waxed ? "waxed" : "weathering", "()L" + COPPER_COLLECTION + "$ByState;", false);
                            delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COPPER_COLLECTION + "$ByState", state,
                                    "()Ljava/lang/Object;", false);
                            delegate.visitTypeInsn(Opcodes.CHECKCAST, type.getInternalName());
                            return true;
                        }
                    }

                    return false;
                }

                /** Removes a call's operands, deepest last, plus the receiver. */
                /** The call's arguments packed into an array and handed to {@code LegacyModels}, with the receiver. */
                private void bridgedCall(String owner, String name, String descriptor, boolean hasReceiver) {
                    Type[] arguments = Type.getArgumentTypes(descriptor);
                    int[] slots = new int[arguments.length];
                    int slot = nextFree;

                    for (int i = 0; i < arguments.length; i++) {
                        slots[i] = slot;
                        slot += arguments[i].getSize();
                    }

                    for (int i = arguments.length - 1; i >= 0; i--) {
                        super.visitVarInsn(arguments[i].getOpcode(Opcodes.ISTORE), slots[i]);
                    }

                    nextFree = slot;

                    if (!hasReceiver) {
                        super.visitInsn(Opcodes.ACONST_NULL);
                    }

                    super.visitLdcInsn(owner);
                    super.visitLdcInsn(name);
                    super.visitLdcInsn(arguments.length);
                    super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

                    for (int i = 0; i < arguments.length; i++) {
                        super.visitInsn(Opcodes.DUP);
                        super.visitLdcInsn(i);
                        super.visitVarInsn(arguments[i].getOpcode(Opcodes.ILOAD), slots[i]);
                        String boxed = boxOf(arguments[i]);

                        if (boxed != null) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, boxed, "valueOf",
                                    "(" + arguments[i].getDescriptor() + ")L" + boxed + ";", false);
                        }

                        super.visitInsn(Opcodes.AASTORE);
                    }

                    String helper = "studios/milkdromeda/octo/bridge/client/LegacyModels";
                    String call = "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)";
                    Type returned = Type.getReturnType(descriptor);

                    switch (returned.getSort()) {
                        case Type.VOID -> super.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "callV", call + "V", false);
                        case Type.BOOLEAN -> super.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "callZ", call + "Z",
                                false);
                        case Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "callI", call + "I", false);

                            if (returned.getSort() == Type.BYTE) {
                                super.visitInsn(Opcodes.I2B);
                            } else if (returned.getSort() == Type.CHAR) {
                                super.visitInsn(Opcodes.I2C);
                            } else if (returned.getSort() == Type.SHORT) {
                                super.visitInsn(Opcodes.I2S);
                            }
                        }
                        case Type.LONG -> super.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "callJ", call + "J", false);
                        case Type.FLOAT -> super.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "callF", call + "F",
                                false);
                        case Type.DOUBLE -> super.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "callD", call + "D",
                                false);
                        default -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "call", call + "Ljava/lang/Object;",
                                    false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, returned.getSort() == Type.ARRAY
                                    ? returned.getDescriptor() : returned.getInternalName());
                        }
                    }
                }

                private String boxOf(Type type) {
                    return switch (type.getSort()) {
                        case Type.BOOLEAN -> "java/lang/Boolean";
                        case Type.BYTE -> "java/lang/Byte";
                        case Type.CHAR -> "java/lang/Character";
                        case Type.SHORT -> "java/lang/Short";
                        case Type.INT -> "java/lang/Integer";
                        case Type.LONG -> "java/lang/Long";
                        case Type.FLOAT -> "java/lang/Float";
                        case Type.DOUBLE -> "java/lang/Double";
                        default -> null;
                    };
                }

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
                /**
                 * A made-up constructor argument: a constant of its type where
                 * it has one ({@link GapBridge#sample}), since a null is most
                 * often a crash inside the constructor it is passed to.
                 */
                private void pushSample(Type type, String owner) {
                    if (classVersion < Opcodes.V1_5 || type.getSort() != Type.OBJECT || Nothing.isSpecial(type)) {
                        pushDefault(type, owner);
                        return;
                    }

                    if (Nothing.pushLive(delegate, type, owner)) {
                        return;
                    }

                    delegate.visitLdcInsn(type);
                    delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/transform/GapBridge",
                            "sample", "(Ljava/lang/Class;)Ljava/lang/Object;", false);
                    delegate.visitTypeInsn(Opcodes.CHECKCAST, type.getInternalName());
                }

                private void pushDefault(Type type, String owner) {
                    if (type.getSort() == Type.OBJECT && Nothing.pushLive(delegate, type, owner)) {
                        return;
                    }

                    Nothing.push(delegate, type);
                }

                /**
                 * What a call that is not made returns. Where the declared type is
                 * an interface that exists, that is an object that does nothing:
                 * {@code registry.playS2C().register(...)} on a null is a second
                 * crash one call after the first was avoided.
                 */
                private void pushResult(Type type, String owner) {
                    // A class constant needs a Java 5 class file or newer.
                    if (classVersion >= Opcodes.V1_5 && type.getSort() == Type.OBJECT && !Nothing.isSpecial(type)
                            && Boolean.TRUE.equals(lookup.isInterface(type.getInternalName()))) {
                        delegate.visitLdcInsn(type);
                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "studios/milkdromeda/octo/bridge/forge/LiveStandIns", "silentOrNull",
                                "(Ljava/lang/Class;)Ljava/lang/Object;", false);
                        delegate.visitTypeInsn(Opcodes.CHECKCAST, type.getInternalName());
                        return;
                    }

                    pushDefault(type, owner);
                }
            };
        }

        /**
         * Another descriptor of {@code name} on {@code owner} that a call made
         * with {@code descriptor} can be pointed at unchanged: the same number
         * of parameters, each one the caller's argument or a supertype of it,
         * and a result the caller can use.
         *
         * @param castAllowed whether a result declared wider than the caller's
         *                    may be cast down to it, which a call site can do
         *                    and a method reference cannot
         * @return that descriptor, or {@code null} when there is none
         */
        private String compatibleOverload(String owner, String name, String descriptor, boolean wantsStatic,
                boolean castAllowed) {
            Type[] arguments = Type.getArgumentTypes(descriptor);
            Type wanted = Type.getReturnType(descriptor);
            boolean constructor = name.equals("<init>");

            for (String candidate : lookup.overloads(owner, name)) {
                Type[] parameters = Type.getArgumentTypes(candidate);

                if (candidate.equals(descriptor) || parameters.length != arguments.length
                        || !usableResult(Type.getReturnType(candidate), wanted, castAllowed)
                        || (!constructor && !Boolean.valueOf(wantsStatic).equals(lookup.isStatic(owner, name,
                        candidate)))) {
                    continue;
                }

                boolean fits = true;

                for (int i = 0; i < parameters.length && fits; i++) {
                    fits = accepts(parameters[i], arguments[i]);
                }

                if (fits) {
                    return candidate;
                }
            }

            return null;
        }

        private boolean accepts(Type parameter, Type argument) {
            if (parameter.equals(argument)) {
                return true;
            }

            if (!isReference(parameter) || !isReference(argument)) {
                return false;
            }

            if (parameter.getDescriptor().equals("Ljava/lang/Object;")) {
                return true;
            }

            return parameter.getSort() == Type.OBJECT && argument.getSort() == Type.OBJECT
                    && lookup.isAssignable(argument.getInternalName(), parameter.getInternalName());
        }

        private boolean usableResult(Type returned, Type wanted, boolean castAllowed) {
            if (returned.equals(wanted) || wanted.getSort() == Type.VOID) {
                return true;
            }

            if (returned.getSort() != Type.OBJECT || wanted.getSort() != Type.OBJECT) {
                return false;
            }

            if (lookup.isAssignable(returned.getInternalName(), wanted.getInternalName())) {
                return true;
            }

            // Declared wider than the caller wants, and quite possibly still
            // what it wants. Unrelated types — an Optional where a Component
            // was — are a ClassCastException, not a repair.
            return castAllowed && lookup.isAssignable(wanted.getInternalName(), returned.getInternalName());
        }

        private static boolean isReference(Type type) {
            return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
        }

        /**
         * Whether the member a call reaches exists but may not be called from
         * this class: private to another class, or package-private — or, for a
         * constructor, protected — in another package.
         */
        /**
         * Whether the JVM would refuse this class the field: private to
         * another nest, package-private to another package, or protected
         * in a class this one does not descend from.
         */
        private boolean fieldInaccessible(String owner, String name, String descriptor) {
            // A class constant, which reaching it by reflection needs, wants Java 5.
            Integer access = classVersion < Opcodes.V1_5 ? null : lookup.fieldAccess(owner, name, descriptor);

            if (access == null || (access & Opcodes.ACC_PUBLIC) != 0) {
                return false;
            }

            if ((access & Opcodes.ACC_PRIVATE) != 0) {
                return !nestOf(owner).equals(nestOf(className));
            }

            if (packageOf(owner).equals(packageOf(className))) {
                return false;
            }

            if ((access & Opcodes.ACC_PROTECTED) == 0) {
                return true;
            }

            // Protected: open to subclasses, so closed only when the class
            // provably is not one — its ancestry read all the way to Object.
            String ancestor = superName;

            for (int depth = 0; ancestor != null && depth < 32; depth++) {
                if (ancestor.equals(owner)) {
                    return false;
                }

                if (ancestor.equals("java/lang/Object")) {
                    return true;
                }

                ancestor = lookup.superName(ancestor);
            }

            return false;
        }

        private boolean inaccessible(String owner, String name, String descriptor) {
            Integer access = lookup.methodAccess(owner, name, descriptor);

            if (access == null || (access & Opcodes.ACC_PUBLIC) != 0) {
                return false;
            }

            // Private reaches across a nest: since Java 11 an inner class calls
            // its outer class's private methods directly, and ModConfigSpec's
            // Builder does exactly that.
            if ((access & Opcodes.ACC_PRIVATE) != 0) {
                return !nestOf(owner).equals(nestOf(className));
            }

            boolean samePackage = packageOf(owner).equals(packageOf(className));
            return (access & Opcodes.ACC_PROTECTED) != 0 ? name.equals("<init>") && !samePackage : !samePackage;
        }

        /**
         * The public static method that builds an {@code owner} from the same
         * arguments a constructor took. {@code try} methods answer null rather
         * than fail, which the caller never expected of a constructor, so they
         * are not chosen.
         */
        private String staticFactory(String owner, String constructor) {
            String parameters = constructor.substring(0, constructor.indexOf(')') + 1);
            String best = null;
            int bestRank = Integer.MAX_VALUE;

            for (String candidate : lookup.staticFactories(owner, parameters)) {
                if (candidate.startsWith("try")) {
                    continue;
                }

                int rank = candidate.equals("of") ? 0 : candidate.equals("parse") ? 1
                        : candidate.startsWith("from") ? 2 : candidate.startsWith("create") ? 3
                        : candidate.startsWith("new") ? 4 : candidate.startsWith("with") ? 6 : 5;

                if (rank < bestRank) {
                    best = candidate;
                    bestRank = rank;
                }
            }

            return best;
        }

        /**
         * Whether a call on {@code owner} returning {@code type} is a builder
         * handing itself back. A getter that happens to return its own type —
         * {@code tag.getCompound("child")} — is not: answering it with the
         * receiver would hand the mod the parent where it asked for the child.
         */
        private boolean returnsReceiver(String owner, String name, String descriptor) {
            Type type = Type.getReturnType(descriptor);

            if (type.getSort() != Type.OBJECT || !type.getInternalName().equals(owner)) {
                return false;
            }

            // "is" and "has" ask something only when nothing is passed: given
            // an argument and answering the builder, they set it —
            // BlockBehaviour.Properties.isViewBlocking(predicate) and its kin,
            // which as nulls stopped every block built with them.
            boolean setter = Type.getArgumentTypes(descriptor).length > 0
                    && (name.startsWith("is") || name.startsWith("has"));

            for (String query : QUERY_PREFIXES) {
                if (name.startsWith(query) && !setter) {
                    return false;
                }
            }

            return true;
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

    /** A method added to the caller that reaches a member across a static/instance change. */
    private record Bridge(String name, String descriptor, String owner, String method, String methodDescriptor,
            String singleton, boolean ownerIsInterface) {
    }

    /** An object {@code NEW} made, waiting for its constructor. */
    private static final class PendingNew {
        final String type;
        /** Whether {@code DUP} followed, so the caller keeps the object once it is built. */
        boolean duplicated;

        PendingNew(String type) {
            this.type = type;
        }
    }

    /** Method names that ask for something rather than configure the receiver. */
    private static final List<String> QUERY_PREFIXES = List.of("get", "is", "has", "find", "read", "copy", "to",
            "as", "contains", "compute", "parse", "of", "from", "create", "load", "lookup", "resolve");

    /** The top-level class whose nest a class belongs to, as javac nests them. */
    private static String nestOf(String internalName) {
        int dollar = internalName.indexOf('$', internalName.lastIndexOf('/') + 1);
        return dollar < 0 ? internalName : internalName.substring(0, dollar);
    }

    /** {@code light_blue} as {@code lightBlue}. */
    private static String camelCase(String name) {
        StringBuilder out = new StringBuilder(name.length());
        boolean upper = false;

        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }

        return out.toString();
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private static String argumentList(Type[] arguments, int count) {
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < count; i++) {
            out.append(arguments[i].getDescriptor());
        }

        return out.toString();
    }

    private static String wrapperOf(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.CHAR -> "java/lang/Character";
            case Type.BYTE -> "java/lang/Byte";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
    }

    private static String factoryDescriptor(String owner, String constructor) {
        return constructor.substring(0, constructor.indexOf(')') + 1) + "L" + owner + ";";
    }

    private static void pushInt(MethodVisitor method, int value) {
        if (value <= 5) {
            method.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE) {
            method.visitIntInsn(Opcodes.BIPUSH, value);
        } else {
            method.visitIntInsn(Opcodes.SIPUSH, value);
        }
    }

    private static void box(MethodVisitor method, Type type) {
        String wrapper = wrapperOf(type);

        if (wrapper != null) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, "valueOf",
                    "(" + type.getDescriptor() + ")L" + wrapper + ";", false);
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
                if (name.equals("<init>")) {
                    return hasConstructor(type, descriptor);
                }

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

            if (name.equals("<init>")) {
                return Boolean.FALSE;
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

        /**
         * A loader-API class Octo does not have and nothing on the class path
         * has either — Forge's {@code net.minecraftforge.fml.Logging}, which
         * the Forge API jar's {@code ForgeConfigSpec} reads a marker from. The
         * only alternative is a stand-in invented on demand, which has no
         * members at all.
         */
        @Override
        public boolean isAbsent(String owner, TransformContext context) {
            return resolve(owner) == null && !context.classExists().test(owner);
        }

        @Override
        public List<String> overloads(String owner, String name) {
            Class<?> type = resolve(owner);

            if (type == null) {
                return List.of();
            }

            try {
                List<String> out = new java.util.ArrayList<>();

                if (name.equals("<init>")) {
                    for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                        out.add(Type.getConstructorDescriptor(constructor));
                    }
                } else {
                    for (java.lang.reflect.Method method : type.getMethods()) {
                        if (method.getName().equals(name)) {
                            out.add(Type.getMethodDescriptor(method));
                        }
                    }
                }

                return out;
            } catch (LinkageError e) {
                return List.of();
            }
        }

        @Override
        public boolean isAssignable(String type, String ancestor) {
            if (type.equals(ancestor) || ancestor.equals("java/lang/Object")) {
                return true;
            }

            Class<?> from = resolve(type);
            Class<?> to = resolve(ancestor);
            return from != null && to != null && to.isAssignableFrom(from);
        }

        private static boolean hasConstructor(Class<?> type, String descriptor) {
            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Type.getConstructorDescriptor(constructor).equals(descriptor)) {
                    return true;
                }
            }

            return false;
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

        @Override
        public String superName(String owner) {
            MemberIndex.Shape shape = index.shape(owner);
            return shape == null ? null : shape.superName();
        }

        /**
         * Only classes the index can see the whole of: an owner whose ancestry
         * runs into something unreadable may inherit the member from there, and
         * a call is never neutered on a guess.
         */
        @Override
        public boolean guards(String owner) {
            // The game and every other mod's API: both move between versions.
            // The loader APIs have a guard of their own.
            return !isLoaderApi(owner) && index.exists(owner) && index.isFullyKnown(owner);
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

        @Override
        public List<String> overloads(String owner, String name) {
            return index.methodDescriptors(owner, name);
        }

        @Override
        public boolean isAssignable(String type, String ancestor) {
            return index.isSubtype(type, ancestor);
        }

        @Override
        public Integer methodAccess(String owner, String name, String descriptor) {
            return index.methodAccess(owner, name, descriptor);
        }

        @Override
        public Integer fieldAccess(String owner, String name, String descriptor) {
            return index.fieldAccess(owner, name, descriptor);
        }

        @Override
        public List<String> staticFactories(String owner, String parameters) {
            return index.staticFactories(owner, parameters);
        }
    }
}
