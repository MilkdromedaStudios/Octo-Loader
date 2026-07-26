package net.fabricmc.loader.impl;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import studios.milkdromeda.octo.compat.fabric.FabricModContainer;
import studios.milkdromeda.octo.compat.fabric.OctoEntrypointContainer;
import studios.milkdromeda.octo.compat.fabric.OctoMappingResolver;
import studios.milkdromeda.octo.compat.fabric.OctoObjectShare;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * Fabric Loader's entrypoint into Octo.
 *
 * <p>Upstream's {@code FabricLoader.getInstance()} reads
 * {@code FabricLoaderImpl.INSTANCE} directly, so this class has to live at this
 * exact name. Everything it is asked for is answered from {@link OctoRuntime},
 * which is what makes {@code FabricLoader.getInstance().isModLoaded("jei")}
 * return true for a Forge mod.
 */
public final class FabricLoaderImpl implements FabricLoader {
    public static final FabricLoaderImpl INSTANCE = new FabricLoaderImpl();

    private final ObjectShare objectShare = new OctoObjectShare();
    private final MappingResolver mappingResolver = new OctoMappingResolver();

    private FabricLoaderImpl() {
    }

    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        List<T> out = new ArrayList<>();

        for (EntrypointContainer<T> container : getEntrypointContainers(key, type)) {
            out.add(container.getEntrypoint());
        }

        return out;
    }

    @Override
    public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        List<EntrypointContainer<T>> out = new ArrayList<>();

        for (OctoRuntime.Entrypoint entrypoint : OctoRuntime.get().entrypoints(key)) {
            if (type.isInstance(entrypoint.instance())) {
                out.add(new OctoEntrypointContainer<>(type.cast(entrypoint.instance()), entrypoint.mod()));
            }
        }

        return out;
    }

    @Override
    public <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
        for (EntrypointContainer<T> container : getEntrypointContainers(key, type)) {
            invoker.accept(container.getEntrypoint());
        }
    }

    @Override
    public ObjectShare getObjectShare() {
        return objectShare;
    }

    @Override
    public MappingResolver getMappingResolver() {
        return mappingResolver;
    }

    @Override
    public Optional<ModContainer> getModContainer(String id) {
        return OctoRuntime.get().mod(id).map(FabricModContainer::of);
    }

    @Override
    public Collection<ModContainer> getAllMods() {
        List<ModContainer> out = new ArrayList<>();

        for (LoadedMod mod : OctoRuntime.get().loadedMods()) {
            out.add(FabricModContainer.of(mod));
        }

        return out;
    }

    @Override
    public boolean isModLoaded(String id) {
        return OctoRuntime.isRunning() && OctoRuntime.get().isModLoaded(id);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return OctoRuntime.get().isDevelopment();
    }

    @Override
    public EnvType getEnvironmentType() {
        return OctoRuntime.get().side() == Side.SERVER ? EnvType.SERVER : EnvType.CLIENT;
    }

    @Override
    public Object getGameInstance() {
        return OctoRuntime.get().gameInstance();
    }

    @Override
    public Path getGameDir() {
        return OctoRuntime.get().gameDir();
    }

    @Override
    @Deprecated
    public File getGameDirectory() {
        return getGameDir().toFile();
    }

    @Override
    public Path getConfigDir() {
        Path configDir = OctoRuntime.get().configDir();

        try {
            java.nio.file.Files.createDirectories(configDir);
        } catch (java.io.IOException e) {
            throw new RuntimeException("could not create config directory " + configDir, e);
        }

        return configDir;
    }

    @Override
    @Deprecated
    public File getConfigDirectory() {
        return getConfigDir().toFile();
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        List<String> arguments = OctoRuntime.get().context().launchArguments();

        if (!sanitize) {
            return arguments.toArray(new String[0]);
        }

        // Drop credentials before handing the command line to a mod.
        List<String> out = new ArrayList<>(arguments.size());

        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);

            if (argument.equals("--accessToken") || argument.equals("--session")
                    || argument.equals("--uuid") || argument.equals("--xuid")
                    || argument.equals("--clientId")) {
                i++;
                continue;
            }

            out.add(argument);
        }

        return out.toArray(new String[0]);
    }

    @Override
    public String getRawGameVersion() {
        return OctoRuntime.get().minecraftVersion();
    }
}
