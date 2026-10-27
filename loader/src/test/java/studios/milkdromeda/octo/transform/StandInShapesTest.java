package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The shape of the classes Octo stands in for, and of the calls it repairs.
 *
 * <p>Each of these came off a real launch: Balm's {@code BlockCapability}
 * handed to a {@code BaseCapability} parameter, a config builder chained four
 * calls deep, {@code NeoForge.EVENT_BUS} read in a mod constructor, and
 * NeoForge's {@code FMLLoader.getDist()} turning from a static into an instance
 * method between two releases.
 */
class StandInShapesTest {
    private static final Set<String> ABSENT = Set.of("api/BaseCapability", "api/BlockCapability", "api/Builder",
            "api/Holder", "api/Factory");

    /** Defines generated classes on demand, the way OctoClassLoader does. */
    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();
        private final PhantomClasses phantoms;

        Loader(PhantomClasses phantoms) {
            super(StandInShapesTest.class.getClassLoader());
            this.phantoms = phantoms;
        }

        void add(String internalName, byte[] bytes) {
            classes.put(internalName, bytes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal = name.replace('.', '/');
            byte[] bytes = classes.get(internal);

            if (bytes == null && phantoms.canGenerate(internal)) {
                bytes = phantoms.generate(internal);
            }

            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }

            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /**
     * A mod class that creates a block capability, hands it to a method taking
     * the base type, chains a builder, and reads a static bus.
     */
    private static byte[] modClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/User", null, "java/lang/Object", null);

        MethodVisitor capability = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "capability",
                "()Ljava/lang/Object;", null, null);
        capability.visitCode();
        capability.visitMethodInsn(Opcodes.INVOKESTATIC, "api/BlockCapability", "create",
                "()Lapi/BlockCapability;", false);
        capability.visitMethodInsn(Opcodes.INVOKESTATIC, "api/Holder", "register",
                "(Lapi/BaseCapability;)Lapi/BaseCapability;", false);
        capability.visitInsn(Opcodes.ARETURN);
        capability.visitMaxs(0, 0);
        capability.visitEnd();

        MethodVisitor builder = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "builder",
                "()Ljava/lang/Object;", null, null);
        builder.visitCode();
        builder.visitTypeInsn(Opcodes.NEW, "api/Builder");
        builder.visitInsn(Opcodes.DUP);
        builder.visitMethodInsn(Opcodes.INVOKESPECIAL, "api/Builder", "<init>", "()V", false);
        builder.visitLdcInsn("a comment");
        builder.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "api/Builder", "comment", "(Ljava/lang/String;)Lapi/Builder;",
                false);
        builder.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "api/Builder", "push", "()Lapi/Builder;", false);
        builder.visitInsn(Opcodes.ARETURN);
        builder.visitMaxs(0, 0);
        builder.visitEnd();

        MethodVisitor factory = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "factory",
                "()Ljava/lang/Object;", null, null);
        factory.visitCode();
        factory.visitMethodInsn(Opcodes.INVOKESTATIC, "api/Holder", "factory", "()Lapi/Factory;", false);
        factory.visitInsn(Opcodes.ARETURN);
        factory.visitMaxs(0, 0);
        factory.visitEnd();

        // Factory is only ever called through an interface call, so it is an interface.
        MethodVisitor useFactory = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "useFactory",
                "(Lapi/Factory;)V", null, null);
        useFactory.visitCode();
        useFactory.visitVarInsn(Opcodes.ALOAD, 0);
        useFactory.visitMethodInsn(Opcodes.INVOKEINTERFACE, "api/Factory", "make", "()V", true);
        useFactory.visitInsn(Opcodes.RETURN);
        useFactory.visitMaxs(0, 0);
        useFactory.visitEnd();

        MethodVisitor bus = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bus",
                "()Ljava/lang/Object;", null, null);
        bus.visitCode();
        bus.visitFieldInsn(Opcodes.GETSTATIC, "api/Holder", "EVENT_BUS", "Lnet/neoforged/bus/api/IEventBus;");
        bus.visitInsn(Opcodes.ARETURN);
        bus.visitMaxs(0, 0);
        bus.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Class<?> user() throws Exception {
        PhantomClasses phantoms = new PhantomClasses();
        byte[] mod = modClass();
        phantoms.observe(mod, name -> !ABSENT.contains(name));

        Loader loader = new Loader(phantoms);
        loader.add("mod/User", mod);
        return loader.loadClass("mod.User");
    }

    @Test
    @DisplayName("a stand-in passed where another is expected is generated as its subtype, so the class verifies")
    void inheritanceIsInferred() throws Exception {
        Class<?> user = user();

        // Running it at all is the verifier agreeing that a BlockCapability is
        // a BaseCapability.
        assertNotNull(user.getMethod("capability").invoke(null));
        assertEquals("api.BaseCapability",
                user.getClassLoader().loadClass("api.BlockCapability").getSuperclass().getName());
    }

    @Test
    @DisplayName("a builder method on a stand-in hands back the builder, so the chain keeps going")
    void buildersReturnThemselves() throws Exception {
        Object built = user().getMethod("builder").invoke(null);

        assertNotNull(built);
        assertEquals("api.Builder", built.getClass().getName());
    }

    @Test
    @DisplayName("a factory returning a stand-in interface returns an implementation, not null")
    void interfacesHaveImplementations() throws Exception {
        Object made = user().getMethod("factory").invoke(null);

        assertNotNull(made);
        assertTrue(made.getClass().getName().endsWith(PhantomClasses.IMPLEMENTATION_SUFFIX));
    }

    @Test
    @DisplayName("an event bus field on a stand-in holds a working bus")
    void busFieldsAreLive() throws Exception {
        Object bus = user().getMethod("bus").invoke(null);

        assertNotNull(bus);
        assertTrue(bus instanceof net.neoforged.bus.api.IEventBus);
    }

    // ---------------------------------------------------------- call repairs

    /** A caller of {@code FMLLoader.getDist()} the way 1.21.1 mods were compiled: static. */
    private static byte[] staticDistCaller() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Old", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dist",
                "()Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/neoforged/fml/loading/FMLLoader", "getDist",
                "()Lnet/neoforged/api/distmarker/Dist;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        MethodVisitor builder = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bus",
                "()Ljava/lang/Object;", null, null);
        builder.visitCode();
        builder.visitMethodInsn(Opcodes.INVOKESTATIC, "net/neoforged/bus/api/BusBuilder", "builder",
                "()Lnet/neoforged/bus/api/BusBuilder;", false);
        // A builder option this version of the API spells differently.
        builder.visitInsn(Opcodes.ACONST_NULL);
        builder.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/neoforged/bus/api/BusBuilder", "classChecker",
                "(Lnet/neoforged/bus/api/IEventClassChecker;)Lnet/neoforged/bus/api/BusBuilder;", false);
        builder.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/neoforged/bus/api/BusBuilder", "build",
                "()Lnet/neoforged/bus/api/IEventBus;", false);
        builder.visitInsn(Opcodes.ARETURN);
        builder.visitMaxs(0, 0);
        builder.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("a static call to what is now a singleton's instance method is reached through the singleton")
    void staticToInstance() throws Exception {
        studios.milkdromeda.octo.runtime.OctoRuntime.initialise(studios.milkdromeda.octo.launch.LaunchContext
                .builder(java.nio.file.Path.of("game")).minecraftVersion("26.3").build());

        try {
            MissingMemberTransformer repair = new MissingMemberTransformer(getClass().getClassLoader());
            byte[] repaired = repair.transform("mod/Old", staticDistCaller(), TransformContext.of(null, name -> true));
            Loader loader = new Loader(new PhantomClasses());
            loader.add("mod/Old", repaired);
            Class<?> old = loader.loadClass("mod.Old");

            assertSame(net.neoforged.fml.loading.FMLEnvironment.dist, old.getMethod("dist").invoke(null));
            assertTrue(old.getMethod("bus").invoke(null) instanceof net.neoforged.bus.api.IEventBus,
                    "a missing builder option should hand the builder back, not null");
        } finally {
            studios.milkdromeda.octo.runtime.OctoRuntime.reset();
        }
    }

    /** Jade's {@code HudRenderCallback.EVENT.register((graphics, delta) -> ...)}, the lambda half of it. */
    private static byte[] lambdaUser() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Hud", null, "java/lang/Object", null);
        String body = "(Ljava/lang/Object;F)V";

        MethodVisitor lambda = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$init$0", body, null, null);
        lambda.visitCode();
        lambda.visitInsn(Opcodes.RETURN);
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();

        MethodVisitor callback = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callback",
                "()Ljava/lang/Object;", null, null);
        callback.visitCode();
        callback.visitInvokeDynamicInsn("onHudRender", "()Lapi/HudCallback;",
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                        "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getMethodType(body),
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "mod/Hud", "lambda$init$0", body, false),
                org.objectweb.asm.Type.getMethodType(body));
        callback.visitInsn(Opcodes.ARETURN);
        callback.visitMaxs(0, 0);
        callback.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("a missing type a lambda is made for is an interface with the lambda's method")
    void lambdaTargetsAreInterfaces() throws Exception {
        PhantomClasses phantoms = new PhantomClasses();
        byte[] mod = lambdaUser();
        phantoms.observe(mod, name -> !name.equals("api/HudCallback"));

        Loader loader = new Loader(phantoms);
        loader.add("mod/Hud", mod);
        Object callback = loader.loadClass("mod.Hud").getMethod("callback").invoke(null);
        Class<?> type = loader.loadClass("api.HudCallback");

        assertTrue(type.isInterface());
        assertTrue(type.isInstance(callback));
        type.getMethod("onHudRender", Object.class, float.class).invoke(callback, "graphics", 1.0f);
    }

    /** Create's {@code handler.getClass()} and a {@code hashCode()} on a NeoForge IItemHandler. */
    private static byte[] objectMethodUser() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Inventory", null, "java/lang/Object", null);

        MethodVisitor describe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "describe",
                "(Lapi/ItemHandler;)Ljava/lang/Object;", null, null);
        describe.visitCode();
        describe.visitVarInsn(Opcodes.ALOAD, 0);
        describe.visitMethodInsn(Opcodes.INVOKEINTERFACE, "api/ItemHandler", "hashCode", "()I", true);
        describe.visitInsn(Opcodes.POP);
        describe.visitVarInsn(Opcodes.ALOAD, 0);
        describe.visitMethodInsn(Opcodes.INVOKEINTERFACE, "api/ItemHandler", "getSlots", "()I", true);
        describe.visitInsn(Opcodes.POP);
        describe.visitVarInsn(Opcodes.ALOAD, 0);
        describe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "api/ItemHandler", "getClass", "()Ljava/lang/Class;", false);
        describe.visitInsn(Opcodes.ARETURN);
        describe.visitMaxs(0, 0);
        describe.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("Object's own methods called on a missing type are left to Object, so the stand-in loads")
    void objectMethodsAreInherited() throws Exception {
        PhantomClasses phantoms = new PhantomClasses();
        byte[] mod = objectMethodUser();
        phantoms.observe(mod, name -> !name.equals("api/ItemHandler"));

        Loader loader = new Loader(phantoms);
        loader.add("mod/Inventory", mod);
        Class<?> handler = loader.loadClass("api.ItemHandler");

        assertTrue(handler.isInterface());
        assertNotNull(handler.getMethod("getSlots"));
        assertTrue(java.util.Arrays.stream(handler.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("getClass") || method.getName().equals("hashCode")));
    }

    private static final String REAL_EVENT = "studios/milkdromeda/octo/transform/RealEvent";

    /** Create's {@code bus.post(new BlockEvent.BreakEvent(...))}, with the event type gone. */
    private static byte[] eventPoster() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Wrench", null, "java/lang/Object", null);

        MethodVisitor post = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "post", "()Z", null, null);
        post.visitCode();
        post.visitTypeInsn(Opcodes.NEW, "api/BreakEvent");
        post.visitInsn(Opcodes.DUP);
        post.visitLdcInsn("broken");
        post.visitMethodInsn(Opcodes.INVOKESPECIAL, "api/BreakEvent", "<init>", "(Ljava/lang/String;)V", false);
        post.visitInsn(Opcodes.DUP);
        post.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "api/BreakEvent", "isCanceled", "()Z", false);
        post.visitInsn(Opcodes.POP);
        post.visitMethodInsn(Opcodes.INVOKESTATIC, REAL_EVENT, "post", "(L" + REAL_EVENT + ";)Z", false);
        post.visitInsn(Opcodes.IRETURN);
        post.visitMaxs(0, 0);
        post.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("a stand-in handed to a method taking a real class extends it, keeping what that class does")
    void realSupertypesAreExtended() throws Exception {
        PhantomClasses phantoms = new PhantomClasses();
        phantoms.useRealClasses(name -> {
            try (java.io.InputStream in = StandInShapesTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
                return in == null ? null : in.readAllBytes();
            } catch (java.io.IOException e) {
                return null;
            }
        });
        byte[] mod = eventPoster();
        phantoms.observe(mod, name -> !name.equals("api/BreakEvent"));

        Loader loader = new Loader(phantoms);
        loader.add("mod/Wrench", mod);

        assertEquals(true, loader.loadClass("mod.Wrench").getMethod("post").invoke(null),
                "the stand-in is a RealEvent, so the real post took it and its real setCanceled ran");
        assertEquals(RealEvent.class, loader.loadClass("api.BreakEvent").getSuperclass());
    }
}
