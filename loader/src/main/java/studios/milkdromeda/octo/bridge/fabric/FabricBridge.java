package studios.milkdromeda.octo.bridge.fabric;

import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import studios.milkdromeda.octo.bridge.Entrypoints;
import studios.milkdromeda.octo.bridge.LoaderBridge;
import studios.milkdromeda.octo.bridge.quilt.QuiltModContainer;
import studios.milkdromeda.octo.mod.EntrypointSpec;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.mod.format.MetadataParser.Phases;
import studios.milkdromeda.octo.runtime.Lifecycle;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Runs Fabric and Quilt mods.
 *
 * <p>The two are handled together because Quilt's entrypoint contract is
 * Fabric's with the mod container passed in: the same mod object often
 * implements both interfaces, and Octo calls whichever it finds.
 */
public final class FabricBridge implements LoaderBridge {
    private static final OctoLog LOG = OctoLog.of(FabricBridge.class);

    @Override
    public String name() {
        return "fabric";
    }

    @Override
    public boolean supports(ModFormat format) {
        return format.family() == ModFormat.Family.FABRIC;
    }

    @Override
    public void construct(LoadedMod mod, ClassLoader classLoader) throws Exception {
        construct(mod, classLoader, Phases.PRELAUNCH, PreLaunchEntrypoint.class);
        construct(mod, classLoader, Phases.MAIN, ModInitializer.class);

        if (OctoRuntime.get().side() == Side.CLIENT) {
            construct(mod, classLoader, Phases.CLIENT, ClientModInitializer.class);
        } else {
            construct(mod, classLoader, Phases.SERVER, DedicatedServerModInitializer.class);
        }

        // Anything under a key Octo does not know is still a valid entrypoint:
        // mods publish their own APIs this way and read them back with
        // FabricLoader.getEntrypoints("theirmod:api", Api.class).
        for (String phase : mod.metadata().entrypoints().keySet()) {
            if (!isKnownPhase(phase)) {
                construct(mod, classLoader, phase, null);
            }
        }
    }

    private static boolean isKnownPhase(String phase) {
        return phase.equals(Phases.PRELAUNCH) || phase.equals(Phases.MAIN)
                || phase.equals(Phases.CLIENT) || phase.equals(Phases.SERVER);
    }

    private void construct(LoadedMod mod, ClassLoader classLoader, String phase, Class<?> expectedType) {
        for (EntrypointSpec spec : mod.metadata().entrypoints(phase)) {
            try {
                Object instance = Entrypoints.create(spec, classLoader, expectedType);
                OctoRuntime.get().registerEntrypoint(phase, mod, instance);
                LOG.debug("{}: constructed {} entrypoint {}", mod.id(), phase, spec);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOG.warn("{}: could not construct {} entrypoint {}: {}", mod.id(), phase, spec, e.toString());
                mod.recordFix("entrypoint " + spec + " could not be constructed: " + e);
            }
        }
    }

    @Override
    public void dispatch(Lifecycle phase, LoadedMod mod) {
        switch (phase) {
            case PRE_LAUNCH -> invoke(mod, Phases.PRELAUNCH, instance -> {
                if (instance instanceof PreLaunchEntrypoint entrypoint) {
                    entrypoint.onPreLaunch();
                }
            });
            case COMMON_SETUP -> invoke(mod, Phases.MAIN, instance -> {
                if (instance instanceof ModInitializer entrypoint) {
                    entrypoint.onInitialize();
                }

                if (instance instanceof org.quiltmc.qsl.base.api.entrypoint.ModInitializer entrypoint) {
                    entrypoint.onInitialize(QuiltModContainer.of(mod));
                }
            });
            case SIDED_SETUP -> {
                if (OctoRuntime.get().side() == Side.CLIENT) {
                    invoke(mod, Phases.CLIENT, instance -> {
                        if (instance instanceof ClientModInitializer entrypoint) {
                            entrypoint.onInitializeClient();
                        }

                        if (instance instanceof org.quiltmc.qsl.base.api.entrypoint.client.ClientModInitializer entrypoint) {
                            entrypoint.onInitializeClient(QuiltModContainer.of(mod));
                        }
                    });
                } else {
                    invoke(mod, Phases.SERVER, instance -> {
                        if (instance instanceof DedicatedServerModInitializer entrypoint) {
                            entrypoint.onInitializeServer();
                        }

                        if (instance instanceof org.quiltmc.qsl.base.api.entrypoint.server.DedicatedServerModInitializer entrypoint) {
                            entrypoint.onInitializeServer(QuiltModContainer.of(mod));
                        }
                    });
                }
            }
            default -> {
                // Fabric has no equivalent of the remaining phases.
            }
        }
    }

    private void invoke(LoadedMod mod, String phase, ThrowingConsumer action) {
        List<OctoRuntime.Entrypoint> entrypoints = OctoRuntime.get().entrypoints(phase);

        for (OctoRuntime.Entrypoint entrypoint : entrypoints) {
            if (entrypoint.mod() != mod) {
                continue;
            }

            try {
                action.accept(entrypoint.instance());
            } catch (Exception | LinkageError e) {
                LOG.error("{}: {} entrypoint failed: {}", mod.id(), phase, e.toString());
                mod.fail(e instanceof Exception exception ? exception : new RuntimeException(e));
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer {
        void accept(Object instance) throws Exception;
    }
}
