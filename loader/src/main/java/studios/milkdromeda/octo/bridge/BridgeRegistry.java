package studios.milkdromeda.octo.bridge;

import java.util.List;

import studios.milkdromeda.octo.bridge.fabric.FabricBridge;
import studios.milkdromeda.octo.bridge.forge.ForgeBridge;
import studios.milkdromeda.octo.bridge.liteloader.LiteLoaderBridge;
import studios.milkdromeda.octo.mod.ModFormat;

/** The set of bridges, and which one owns a given mod. */
public final class BridgeRegistry {
    private final List<LoaderBridge> bridges;

    public BridgeRegistry() {
        this(List.of(new FabricBridge(), new ForgeBridge(), new LiteLoaderBridge()));
    }

    public BridgeRegistry(List<LoaderBridge> bridges) {
        this.bridges = List.copyOf(bridges);
    }

    public List<LoaderBridge> bridges() {
        return bridges;
    }

    public LoaderBridge forFormat(ModFormat format) {
        for (LoaderBridge bridge : bridges) {
            if (bridge.supports(format)) {
                return bridge;
            }
        }

        return null;
    }
}
