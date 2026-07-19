package studios.milkdromeda.octoloader.shims.neoforge;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;

/**
 * Boots translated NeoForge mods: finds every loaded mod carrying Octo's
 * {@code custom.octoloader.neoforge} metadata, constructs its {@code @Mod}
 * entry classes with the constructor shapes NeoForge documents, and fires the
 * lifecycle events on the mod's bus. A failing mod is logged and isolated —
 * it never takes the game down with it.
 */
public final class NeoForgeRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("OctoLoader/NeoForge");

    private NeoForgeRuntime() {
    }

    public static void initAll() {
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            CustomValue custom = mod.getMetadata().getCustomValue("octoloader");
            if (custom == null || custom.getType() != CustomValue.CvType.OBJECT) continue;
            CustomValue neoforge = custom.getAsObject().get("neoforge");
            if (neoforge == null || neoforge.getType() != CustomValue.CvType.OBJECT) continue;
            CustomValue entryClasses = neoforge.getAsObject().get("entryClasses");
            if (entryClasses == null || entryClasses.getType() != CustomValue.CvType.ARRAY) continue;

            String modId = mod.getMetadata().getId();
            for (CustomValue entry : entryClasses.getAsArray()) {
                initEntryClass(modId, entry.getAsString());
            }
        }
    }

    private static void initEntryClass(String modId, String className) {
        try {
            OctoEventBus bus = new OctoEventBus(modId);
            net.neoforged.fml.ModContainer container = new net.neoforged.fml.ModContainer(modId, bus);

            Class<?> cls = Class.forName(className);
            construct(cls, bus, container);
            LOGGER.info("Initialized NeoForge mod '{}' ({})", modId, className);

            bus.post(new FMLCommonSetupEvent());
            if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
                bus.post(new FMLClientSetupEvent());
            } else {
                bus.post(new FMLDedicatedServerSetupEvent());
            }
        } catch (Throwable t) {
            LOGGER.error("NeoForge mod '{}' failed to initialize ({}); it is disabled for this run",
                    modId, className, t);
        }
    }

    /** NeoForge documents these injectable constructor signatures for @Mod classes. */
    private static void construct(Class<?> cls, IEventBus bus, net.neoforged.fml.ModContainer container)
            throws ReflectiveOperationException {
        record Shape(Class<?>[] params, Object[] args) {
        }
        Shape[] shapes = {
                new Shape(new Class<?>[]{IEventBus.class, net.neoforged.fml.ModContainer.class},
                        new Object[]{bus, container}),
                new Shape(new Class<?>[]{net.neoforged.fml.ModContainer.class, IEventBus.class},
                        new Object[]{container, bus}),
                new Shape(new Class<?>[]{IEventBus.class}, new Object[]{bus}),
                new Shape(new Class<?>[]{net.neoforged.fml.ModContainer.class}, new Object[]{container}),
                new Shape(new Class<?>[]{}, new Object[]{}),
        };
        for (Shape shape : shapes) {
            try {
                Constructor<?> ctor = cls.getDeclaredConstructor(shape.params());
                ctor.setAccessible(true);
                ctor.newInstance(shape.args());
                return;
            } catch (NoSuchMethodException ignored) {
                // Try the next documented shape.
            }
        }
        throw new NoSuchMethodException(cls.getName() + " has no supported @Mod constructor");
    }
}
