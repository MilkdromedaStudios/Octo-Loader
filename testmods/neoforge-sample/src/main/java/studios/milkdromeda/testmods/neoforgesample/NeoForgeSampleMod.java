package studios.milkdromeda.testmods.neoforgesample;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod("neoforge_sample")
public class NeoForgeSampleMod {
    public NeoForgeSampleMod(IEventBus modBus) {
        System.out.println("[NeoForgeSample] Constructed by Octo Loader's NeoForge runtime!");
        modBus.addListener(FMLCommonSetupEvent.class,
                event -> System.out.println("[NeoForgeSample] Common setup event received!"));
    }
}
