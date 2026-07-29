package studios.milkdromeda.octo.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.ModLifecycleEvent;
import net.neoforged.fml.event.lifecycle.ParallelDispatchEvent;
import net.neoforged.neoforgespi.language.IModInfo;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/**
 * The parts of Forge and NeoForge that Create, Flywheel and Ponder reach for
 * before they have done anything else.
 *
 * <p>Every one of these was a {@code NoSuchMethodError} rather than a wrong
 * answer, which is why they were worth this much: they are called from static
 * initialisers and mixin configuration plugins, where an error is not a branch
 * that fails but a mod that never loads.
 */
class ForgeApiSurfaceTest {
    @Nested
    @DisplayName("lifecycle events")
    class Lifecycle {
        @Test
        @DisplayName("enqueued work runs, and hands back the future the caller chains onto")
        void enqueuedWorkReturnsAFuture() {
            AtomicBoolean ran = new AtomicBoolean();
            CompletableFuture<Void> future = new FMLCommonSetupEvent("create")
                    .enqueueWork(() -> ran.set(true));

            assertTrue(ran.get(), "Octo runs setup on the calling thread, so the work should already have run");
            assertTrue(future.isDone());
            assertFalse(future.isCompletedExceptionally());
        }

        @Test
        @DisplayName("the supplier form hands back what the work produced")
        void enqueuedWorkCarriesItsValue() {
            assertEquals("built", new FMLCommonSetupEvent("create").enqueueWork(() -> "built").join());
        }

        @Test
        @DisplayName("work that throws fails its future rather than taking the launch with it")
        void failingWorkFailsItsFuture() {
            Runnable throwing = () -> {
                throw new IllegalStateException("no registry yet");
            };
            CompletableFuture<Void> future = new FMLCommonSetupEvent("create").enqueueWork(throwing);

            assertTrue(future.isCompletedExceptionally());
        }

        @Test
        @DisplayName("every setup phase is one kind of event, so a mod's shared handler is called")
        void phasesShareASupertype() {
            assertTrue(new FMLClientSetupEvent("create") instanceof ParallelDispatchEvent);
            assertTrue(new FMLCommonSetupEvent("create") instanceof ModLifecycleEvent);
            assertTrue(new FMLCommonSetupEvent("create") instanceof net.neoforged.fml.event.IModBusEvent);
        }

        @Test
        @DisplayName("an event knows which mod it was posted for")
        void anEventNamesItsMod() {
            FMLCommonSetupEvent event = new FMLCommonSetupEvent("flywheel");

            assertEquals("flywheel", event.getModId());
            assertSame(ForgeBuses.neoContainerFor("flywheel"), event.getContainer());
        }
    }

    @Nested
    @DisplayName("asking another mod about itself")
    class ModInfo {
        @Test
        @DisplayName("a container describes its mod, which is the call that took Create down")
        void containerAnswersModInfo() {
            IModInfo info = ForgeBuses.neoContainerFor("create").getModInfo();

            assertNotNull(info);
            assertEquals("create", info.getModId());
            assertNotNull(info.getVersion(), "IModInfo.getVersion() is read from an enum constructor");
        }

        @Test
        @DisplayName("a mod file lists its mods as descriptions, not as bare ids")
        void modFileListsDescriptions() {
            var file = new net.neoforged.fml.loading.moddiscovery.ModFileInfo("create");

            assertEquals(1, file.getMods().size());
            // getMods().get(0).getVersion() is Create's own idiom, and a list of
            // strings here is exactly the NoSuchMethodError it produced.
            assertEquals("create", file.getMods().get(0).getModId());
            assertNotNull(file.getMods().get(0).getVersion());
        }

        @Test
        @DisplayName("Forge's spelling answers the same way, because Create ships both builds")
        void forgeSpellingAgrees() {
            var file = new net.minecraftforge.fml.loading.moddiscovery.ModFileInfo("create");

            assertEquals(1, file.getMods().size());
            assertEquals("create", file.getMods().get(0).getModId());
            assertNotNull(ForgeBuses.forgeContainerFor("create").getModInfo().getVersion());
        }

        @Test
        @DisplayName("nothing is loaded, so a lookup answers absent rather than throwing")
        void absentModsAnswerAbsent() {
            assertNull(net.neoforged.fml.loading.moddiscovery.ModFileInfo.of("create"));
            assertNull(net.minecraftforge.fml.loading.moddiscovery.ModFileInfo.of("create"));
            assertTrue(net.neoforged.fml.ModList.get().getModContainerById("create").isEmpty());
            assertTrue(net.neoforged.fml.ModList.get().getModObjectById("create").isEmpty());
            assertTrue(net.neoforged.fml.ModList.get().getMods().isEmpty());
        }
    }

    @Nested
    @DisplayName("registering a listener")
    class Listeners {
        /** Cancellable, because that is the only kind the flag means anything for. */
        static final class Cancellable extends net.neoforged.bus.api.Event {
            @Override
            public boolean isCancelable() {
                return true;
            }
        }

        @Test
        @DisplayName("the overloads that also say whether cancelled events are wanted exist")
        void acceptsTheReceiveCancelledOverloads() {
            var bus = ForgeBuses.neoBusFor("jei");
            AtomicBoolean called = new AtomicBoolean();

            // A mod compiled against the real NeoForge calls these, and an overload
            // the bus does not declare is not a compile error for the mod — its
            // call is quietly turned into a no-op and its listener never fires.
            bus.addListener(true, Cancellable.class, event -> called.set(true));
            bus.post(new Cancellable());

            assertTrue(called.get(), "the listener registered through the overload should have been called");
        }

        @Test
        @DisplayName("a listener that said it does not want cancelled events is not given one")
        void respectsWhatTheModAskedFor() {
            var bus = ForgeBuses.neoBusFor("jei");
            AtomicBoolean wants = new AtomicBoolean();
            AtomicBoolean declines = new AtomicBoolean();

            bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, true, Cancellable.class,
                    event -> event.setCanceled(true));
            bus.addListener(true, Cancellable.class, event -> wants.set(true));
            bus.addListener(false, Cancellable.class, event -> declines.set(true));

            Cancellable event = bus.post(new Cancellable());

            assertTrue(event.isCanceled(), "the first listener should have cancelled it");
            assertTrue(wants.get(), "a listener that asked for cancelled events should still get them");
            assertFalse(declines.get(), "a listener that declined cancelled events should have been skipped");
        }
    }

    @Nested
    @DisplayName("the version type Forge returns")
    class Versions {
        @Test
        @DisplayName("a version reads back the way a mod wrote it")
        void versionsRoundTrip() {
            ArtifactVersion version = new DefaultArtifactVersion("1.20.1-0.5.1.f");

            assertEquals(1, version.getMajorVersion());
            assertEquals(20, version.getMinorVersion());
            assertEquals(1, version.getIncrementalVersion());
            assertEquals("0.5.1.f", version.getQualifier());
            assertEquals("1.20.1-0.5.1.f", version.toString());
        }

        @Test
        @DisplayName("versions order the same way here as everywhere else in the loader")
        void versionsCompare() {
            assertTrue(new DefaultArtifactVersion("0.6.0").compareTo(new DefaultArtifactVersion("0.5.9")) > 0);
            assertEquals(0, new DefaultArtifactVersion("1.0.0").compareTo(new DefaultArtifactVersion("1.0.0")));
        }

        @Test
        @DisplayName("a Maven range reads in Forge's dialect")
        void rangesUseMavensDialect() {
            VersionRange range = VersionRange.createFromVersionSpec("[1.20.1,1.21)");

            assertTrue(range.containsVersion(new DefaultArtifactVersion("1.20.4")));
            assertFalse(range.containsVersion(new DefaultArtifactVersion("1.21")));
            assertTrue(range.hasRestrictions());
        }
    }
}
