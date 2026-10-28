package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Loads a Forge or NeoForge mod's config file and hands it to the mod's spec.
 *
 * <p>A spec answers nothing until it has a config: NeoForge's
 * {@code ModConfigSpec.ConfigValue.get()} throws "Cannot get config value
 * before config is loaded", and Create asks its config on every tick of every
 * kinetic block — the first cogwheel placed stopped the server. FML loads
 * each registered config from its TOML file; so does this, as it is
 * registered, writing the defaults out when the file is new. A file that
 * cannot be read or written leaves the mod its defaults, held in memory.
 */
public final class ConfigFiles {
    private static final OctoLog LOG = OctoLog.of(ConfigFiles.class);

    private ConfigFiles() {
    }

    /**
     * @param spec a NeoForge {@code ModConfigSpec} or a Forge {@code ForgeConfigSpec}
     * @param path where its file is, asked only once the spec is known to need one
     */
    public static void load(Object spec, java.util.function.Supplier<Path> path) {
        if (spec == null) {
            return;
        }

        CommentedConfig config;

        try {
            config = open(path.get());
        } catch (IllegalStateException e) {
            // Before Octo has started there is no config directory to read.
            config = CommentedConfig.inMemory();
        }

        if (!hand(spec, config) && config instanceof CommentedFileConfig file) {
            file.close();
        }
    }

    /**
     * Hands a spec a config somebody else read: NeoForge's {@code LoadedConfig},
     * built by a mod applying the values a server sent.
     *
     * @return whether the spec took it
     */
    public static boolean accept(Object spec, Object loadedConfig) {
        for (Method method : spec.getClass().getMethods()) {
            if (method.getParameterCount() == 1 && method.getName().equals("acceptConfig")
                    && method.getParameterTypes()[0].isInstance(loadedConfig)) {
                try {
                    method.invoke(spec, loadedConfig);
                    return true;
                } catch (Throwable e) {
                    Failures.rethrowIfFatal(e);
                    LOG.warn("{} would not take its config: {}", spec.getClass().getName(),
                            Failures.describe(Failures.unwrap(e)));
                    OctoLog.detail(Failures.unwrap(e));
                    return false;
                }
            }
        }

        return false;
    }

    /** Writes a config out as TOML, replacing the file. */
    public static void write(Path path, CommentedConfig config) {
        try {
            Files.createDirectories(path.getParent());
            com.electronwill.nightconfig.toml.TomlFormat.instance().createWriter()
                    .write(config, path, WritingMode.REPLACE);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("config {} could not be written: {}", path.getFileName(), e.toString());
        }
    }

    private static CommentedConfig open(Path path) {
        try {
            Files.createDirectories(path.getParent());
            CommentedFileConfig file = CommentedFileConfig.builder(path).sync().preserveInsertionOrder()
                    .writingMode(WritingMode.REPLACE).build();
            file.load();
            return file;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("config {} could not be read, so its mod runs on its defaults: {}", path.getFileName(),
                    Failures.describe(e));
            return CommentedConfig.inMemory();
        }
    }

    /** @return whether the spec took the config */
    private static boolean hand(Object spec, CommentedConfig config) {
        for (Method method : spec.getClass().getMethods()) {
            if (method.getParameterCount() != 1
                    || !(method.getName().equals("acceptConfig") || method.getName().equals("setConfig"))) {
                continue;
            }

            Class<?> parameter = method.getParameterTypes()[0];
            Object argument = parameter.isInstance(config) ? config
                    : parameter.isInterface() ? loaded(parameter, config) : null;

            if (argument == null) {
                continue;
            }

            try {
                method.invoke(spec, argument);
                return true;
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.warn("{} would not take its config: {}", spec.getClass().getName(),
                        Failures.describe(Failures.unwrap(e)));
                OctoLog.detail(Failures.unwrap(e));
            }
        }

        LOG.debug("{} takes no config Octo knows how to hand it", spec.getClass().getName());
        return false;
    }

    /** FML's {@code IConfigSpec.ILoadedConfig}: the config itself, and a way to save it. */
    private static Object loaded(Class<?> type, CommentedConfig config) {
        try {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, arguments) ->
                switch (method.getName()) {
                    case "config" -> config;
                    case "save" -> {
                        save(config);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "config " + (config instanceof CommentedFileConfig file
                            ? file.getNioPath().getFileName() : "held in memory");
                    default -> null;
                });
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void save(CommentedConfig config) {
        if (config instanceof CommentedFileConfig file) {
            try {
                file.save();
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.debug("config {} could not be saved: {}", file.getNioPath().getFileName(), e.toString());
            }
        }
    }
}
