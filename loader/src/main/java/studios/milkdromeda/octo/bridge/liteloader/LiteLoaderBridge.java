package studios.milkdromeda.octo.bridge.liteloader;

import com.mumfrey.liteloader.LiteMod;

import studios.milkdromeda.octo.bridge.Entrypoints;
import studios.milkdromeda.octo.bridge.LoaderBridge;
import studios.milkdromeda.octo.mod.EntrypointSpec;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.format.MetadataParser.Phases;
import studios.milkdromeda.octo.runtime.Lifecycle;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Runs LiteLoader mods.
 *
 * <p>LiteLoader stopped at 1.12.2 and the litemods written for it have had no
 * home since. Their contract is small — construct, then {@code init(configDir)} —
 * so supporting it costs almost nothing and brings back a whole shelf of mods.
 */
public final class LiteLoaderBridge implements LoaderBridge {
    private static final OctoLog LOG = OctoLog.of(LiteLoaderBridge.class);

    @Override
    public String name() {
        return "liteloader";
    }

    @Override
    public boolean supports(ModFormat format) {
        return format.family() == ModFormat.Family.LITELOADER;
    }

    @Override
    public void construct(LoadedMod mod, ClassLoader classLoader) {
        for (EntrypointSpec spec : mod.metadata().entrypoints(Phases.LITEMOD)) {
            try {
                Object instance = Entrypoints.create(spec, classLoader, LiteMod.class);
                OctoRuntime.get().registerEntrypoint(Phases.LITEMOD, mod, instance);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                Throwable cause = Failures.unwrap(e);
                LOG.error("{}: could not construct {}: {}", mod.id(), spec, Failures.describe(cause));
                OctoLog.detail(cause);
                mod.fail(cause);
            }
        }
    }

    @Override
    public void dispatch(Lifecycle phase, LoadedMod mod) {
        if (phase != Lifecycle.COMMON_SETUP) {
            return;
        }

        for (Object instance : mod.instances()) {
            if (!(instance instanceof LiteMod liteMod)) {
                continue;
            }

            try {
                liteMod.init(OctoRuntime.get().configDir().toFile());
                LOG.debug("{}: initialised litemod {}", mod.id(), liteMod.getName());
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                Throwable cause = Failures.unwrap(e);
                LOG.error("{}: init() threw {}", mod.id(), Failures.describe(cause));
                OctoLog.detail(cause);
                mod.fail(cause);
            }
        }
    }
}
