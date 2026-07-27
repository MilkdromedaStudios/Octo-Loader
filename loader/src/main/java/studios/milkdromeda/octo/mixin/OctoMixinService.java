package studios.milkdromeda.octo.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

import studios.milkdromeda.octo.launch.OctoClassLoader;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Mixin's view of Octo.
 *
 * <p>Mixin does not load classes itself; it asks the host for bytecode, for
 * resources, and for the class loader that will define what it produces. Every
 * loader that supports mixins answers those questions its own way — Fabric
 * through Knot, Forge through ModLauncher — and the answers are what makes the
 * two mutually exclusive. This is Octo's answer, and because there is one of it,
 * a Fabric mod's mixins and a Forge mod's mixins apply to the same game.
 *
 * <p>Mixin discovers this class through {@code META-INF/services}, constructs it
 * with a no-argument constructor, and does so possibly before {@link #attach}
 * has run, so everything here reads the class loader lazily.
 */
public final class OctoMixinService extends MixinServiceAbstract
        implements IClassProvider, IClassBytecodeProvider, IClassTracker {
    private static final OctoLog LOG = OctoLog.of("Mixin");

    private static volatile OctoClassLoader classLoader;
    private static volatile IMixinTransformer transformer;

    private final Set<String> invalidClasses = ConcurrentHashMap.newKeySet();

    /** Tells the service which class loader the game is being loaded in. */
    public static void attach(OctoClassLoader loader) {
        classLoader = loader;
    }

    /** The transformer mixin handed over during bootstrap, or {@code null} before it did. */
    public static IMixinTransformer transformer() {
        return transformer;
    }

    static ClassLoader loader() {
        OctoClassLoader current = classLoader;
        return current != null ? current : OctoMixinService.class.getClassLoader();
    }

    @Override
    public String getName() {
        return "Octo";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    /**
     * Mixin offers its internals here during initialisation; the transformer
     * factory is the one Octo needs, because the class loader has to be able to
     * run mixins itself rather than waiting to be called.
     */
    @Override
    public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory factory) {
            transformer = factory.createTransformer();
        }

        super.offer(internal);
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        // Octo has no other bytecode transformers for mixin to co-operate with:
        // its own run before mixin does, in the class loader.
        return null;
    }

    @Override
    public IClassTracker getClassTracker() {
        return this;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public IFeatureValidator getFeatureValidator() {
        return IFeatureValidator.ALLOW_ALL;
    }

    @Override
    public IAdviceProvider getAdviceProvider() {
        return IAdviceProvider.GENERIC;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return List.of("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault");
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual(getName());
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return loader().getResourceAsStream(name);
    }

    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.PREINIT;
    }

    /** Mixin's own logging, routed into Octo's so it lands in the same file. */
    @Override
    protected ILogger createLogger(String name) {
        return new LoggerAdapter(name);
    }

    // ------------------------------------------------------------ IClassProvider

    @Override
    public URL[] getClassPath() {
        // Deprecated in mixin and used by nothing that Octo enables.
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader());
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, loader());
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        // Platform agents are mixin's own, so they come from the loader's copy
        // rather than from anything a mod put on the class path.
        return Class.forName(name, initialize, OctoMixinService.class.getClassLoader());
    }

    // --------------------------------------------------- IClassBytecodeProvider

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, false, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    /**
     * Reads a class as bytecode, without loading it.
     *
     * <p>The bytes come from the class loader with the mod's own translation
     * already applied, which is what lets a mixin target a class from a mod
     * built for a different Minecraft version: by the time mixin sees it, the
     * names in it are the ones the mixin was written against.
     */
    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int flags)
            throws ClassNotFoundException, IOException {
        OctoClassLoader current = classLoader;
        String internalName = name.replace('.', '/');
        byte[] bytes = current == null ? null : current.rawClassBytes(internalName);

        if (bytes == null) {
            bytes = readFromClassPath(internalName);
        }

        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }

        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, flags);
        return node;
    }

    private static byte[] readFromClassPath(String internalName) throws IOException {
        try (InputStream in = loader().getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    // ------------------------------------------------------------ IClassTracker

    @Override
    public void registerInvalidClass(String className) {
        invalidClasses.add(className);
    }

    @Override
    public boolean isClassLoaded(String className) {
        OctoClassLoader current = classLoader;
        return current != null && current.isClassLoaded(className);
    }

    @Override
    public String getClassRestrictions(String className) {
        // Octo does not sandbox mods from each other, so nothing is restricted.
        return "";
    }

    boolean isInvalid(String className) {
        return invalidClasses.contains(className);
    }

    private static final class LoggerAdapter extends LoggerAdapterAbstract {
        private final OctoLog log;

        LoggerAdapter(String name) {
            super(name);
            this.log = OctoLog.of("Mixin/" + name);
        }

        @Override
        public String getType() {
            return "Octo Logger";
        }

        @Override
        public void log(Level level, String message, Object... params) {
            switch (level) {
                case FATAL, ERROR -> log.error(message, params);
                case WARN -> log.warn(message, params);
                case INFO -> log.info(message, params);
                case DEBUG -> log.debug(message, params);
                default -> log.trace(message, params);
            }
        }

        @Override
        public void log(Level level, String message, Throwable t) {
            log(level, message + ": " + t);
            OctoLog.detail(t);
        }

        @Override
        public <T extends Throwable> T throwing(T t) {
            log(Level.ERROR, "throwing " + t.getClass().getName(), t);
            return t;
        }

        @Override
        public void catching(Level level, Throwable t) {
            log(level, "caught " + t.getClass().getName(), t);
        }
    }
}
