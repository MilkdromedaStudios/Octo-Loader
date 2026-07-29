package studios.milkdromeda.octo.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Turning a stack trace into something a player can act on. */
class CrashReadingTest {
    @Test
    @DisplayName("the registry crash is read as a bootstrap that did not finish")
    void theRegistryDefaultNullPointer() {
        CrashReading reading = CrashReading.of("""
                java.lang.NullPointerException: Cannot invoke "net.minecraft.core.Holder$Reference.value()" \
                because "this.defaultValue" is null
                    at net.minecraft.core.DefaultedMappedRegistry.byId(DefaultedMappedRegistry.java:41)
                    at net.minecraft.client.Minecraft.<init>(Minecraft.java:498)
                """);

        assertTrue(reading.isRecognised());
        assertTrue(reading.meaning().contains("default entry"), reading.meaning());
        assertTrue(reading.meaning().contains("bootstrap"), reading.meaning());
        assertTrue(reading.steps().stream().anyMatch(step -> step.contains("safeMode")),
                "the player needs a way to find out whether it is a mod: " + reading.steps());
    }

    @Test
    @DisplayName("a mixin that would not apply is named as one")
    void aMixinFailure() {
        CrashReading reading = CrashReading.of(
                "org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError: Mixin apply failed "
                        + "sodium.mixins.json:core.MinecraftMixin");

        assertTrue(reading.isRecognised());
        assertTrue(reading.meaning().contains("mixin"), reading.meaning());
    }

    @Test
    @DisplayName("a mod built for another version is told apart from a missing one")
    void versionAndDependencyFailures() {
        assertTrue(CrashReading.of("java.lang.NoSuchMethodError: 'net.minecraft.core.Registry blocks()'")
                .meaning().contains("different version"));
        assertTrue(CrashReading.of("java.lang.ClassNotFoundException: dev.emi.emi.api.EmiPlugin")
                .meaning().contains("not on the class path"));
    }

    @Test
    @DisplayName("the word mixin in a stack is not on its own a mixin failure")
    void mixinInTheStackIsNotAVerdict() {
        CrashReading reading = CrashReading.of("""
                java.lang.NoSuchMethodError: 'void net.minecraft.client.Minecraft.setScreen()'
                    at net.minecraft.client.MinecraftMixin.handler$zzz000(Minecraft.java:1)
                """);

        assertTrue(reading.meaning().contains("different version"),
                "the missing method is the finding, not the class that happens to be a mixin: "
                        + reading.meaning());
    }

    @Test
    @DisplayName("an unrecognised crash says so rather than guessing")
    void unknownFailuresAreNotGuessedAt() {
        CrashReading reading = CrashReading.of("something nobody has seen before");

        assertFalse(reading.isRecognised());
        assertTrue(reading.meaning().contains("does not recognise"), reading.meaning());
        assertFalse(reading.steps().isEmpty(), "there is still something worth trying");
    }

    @Test
    @DisplayName("nothing to read is not a crash")
    void emptyText() {
        assertFalse(CrashReading.of(null).isRecognised());
        assertFalse(CrashReading.of("   ").isRecognised());
    }
}
