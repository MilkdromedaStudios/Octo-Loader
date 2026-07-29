package studios.milkdromeda.octo.report;

import java.util.List;
import java.util.Locale;

/**
 * What a crash actually says, in words the person reading it can act on.
 *
 * <p>A stack trace is evidence, not an explanation. {@code Cannot invoke
 * "net.minecraft.core.Holder$Reference.value()" because "this.defaultValue" is
 * null} is precise, correct, and tells a player nothing at all — least of all
 * that the answer is usually one mod out of forty. Every entry below is a
 * signature that has a known meaning and a next step, so the window can lead
 * with the meaning and keep the evidence underneath.
 *
 * <p>Nothing here guesses. When no signature matches, the reading says so rather
 * than inventing a cause, because a confident wrong answer costs more time than
 * no answer.
 */
public record CrashReading(String meaning, List<String> steps) {
    /**
     * @param fragments all of which must appear in the crash text, lower-cased
     */
    private record Rule(List<String> fragments, String meaning, List<String> steps) {
        boolean matches(String text) {
            return fragments.stream().allMatch(text::contains);
        }
    }

    private static final String SAFE_MODE =
            "Start once with -Docto.safeMode=true. If the game starts, a mod is the cause; if it crashes the "
                    + "same way, it is not.";

    private static final String MIXIN_MEANING =
            "A mod rewrites part of Minecraft with a mixin, and that mixin does not fit the version you are "
                    + "running: the method or field it aims at has moved, changed shape, or is gone.";

    private static final List<String> MIXIN_STEPS = List.of(
            "The mixin class named in the failure says which mod it belongs to.",
            "Look for a build of that mod made for this Minecraft version.",
            SAFE_MODE);

    private static final List<Rule> RULES = List.of(
            new Rule(List.of("defaultvalue"),
                    "Minecraft asked a registry for its default entry — air for blocks, empty for fluids — and "
                            + "that entry was never registered. The game's registry bootstrap did not finish, so "
                            + "the registries were only half built by the time the game used them.",
                    List.of("Search the Octo log for \"bootstrap\": whatever threw during it is logged there, "
                                    + "with the mod it came from.",
                            SAFE_MODE,
                            "If safe mode starts, halve the mods folder until the crash follows one jar.")),

            new Rule(List.of("not bootstrapped"),
                    "Something reached one of Minecraft's registries before the game had built them. Usually a "
                            + "mod registering content from its constructor rather than from the event the loader "
                            + "gives it for that.",
                    List.of("The mod named in the stack above this line is the one that reached too early.",
                            SAFE_MODE)),

            new Rule(List.of("frozen"),
                    "A mod tried to add content to a registry after Minecraft had sealed it. Registration has to "
                            + "happen during start-up, and this one arrived after the game had moved on.",
                    List.of("The mod named in the stack is registering too late for this Minecraft version.",
                            "A newer build of that mod, if there is one for this version, normally fixes it.")),

            // Mixin failures are named precisely rather than by the word "mixin",
            // which appears in the stack of half the crashes in a modded game
            // without being the cause of any of them.
            new Rule(List.of("mixin apply failed"), MIXIN_MEANING, MIXIN_STEPS),
            new Rule(List.of("mixin transformation of"), MIXIN_MEANING, MIXIN_STEPS),
            new Rule(List.of("injection failure"), MIXIN_MEANING, MIXIN_STEPS),
            new Rule(List.of("org.spongepowered.asm.mixin.injection.throwables"), MIXIN_MEANING, MIXIN_STEPS),
            new Rule(List.of("mixinapplyerror"), MIXIN_MEANING, MIXIN_STEPS),

            new Rule(List.of("unsupportedclassversionerror"),
                    "A mod was compiled for a newer Java than the one running the game.",
                    List.of("Point your launcher's Java runtime at a newer JDK — the launcher's installation "
                                    + "settings choose this per profile.",
                            "The number in the message is the class file version: 61 is Java 17, 65 is Java 21.")),

            new Rule(List.of("outofmemoryerror"),
                    "The game ran out of memory rather than hitting a bug. Large mod folders, and anything that "
                            + "generates world data at start-up, need more than the launcher's default.",
                    List.of("Raise the maximum heap in the launcher's JVM arguments, for example -Xmx6G.",
                            "If it happens at a fixed point every time, it is worth suspecting one mod as well.")),

            new Rule(List.of("nosuchmethoderror"),
                    "A mod called into Minecraft, or into another mod, and what it called is not there. That is "
                            + "the signature of a mod built against a different version than the one running.",
                    List.of("The class in the message says where the mismatch is: net.minecraft means the mod is "
                                    + "for another Minecraft, anything else means a mod dependency is the wrong "
                                    + "version.",
                            "Octo's report lists every mod it had to translate; one of those is the usual "
                                    + "suspect.")),

            new Rule(List.of("nosuchfielderror"),
                    "A mod read a field that this Minecraft does not have, which means it was built against a "
                            + "different version.",
                    List.of("Look for a build of the mod named in the stack that matches this Minecraft.")),

            new Rule(List.of("classnotfoundexception"),
                    "A class a mod needs is not on the class path. Almost always a required library or a "
                            + "companion mod that was not installed.",
                    List.of("The missing class name usually names the project it belongs to — install that mod "
                                    + "or library alongside.",
                            "Octo's report lists dependencies it could not satisfy; check there first.")),

            new Rule(List.of("noclassdeffounderror"),
                    "A class failed to load, either because it is missing or because it failed the first time "
                            + "anything touched it. When it is the second, the real failure is earlier in the log.",
                    List.of("Search the Octo log for the same class name: the first failure is the real one.",
                            "If a library mod is named, it is probably missing or the wrong version.")),

            new Rule(List.of("incompatibleclasschangeerror"),
                    "Two different versions of the same code are loaded at once — usually the same library "
                            + "shipped inside two mods, or a mod installed twice.",
                    List.of("Check the mods folder for two copies of one mod, including one with a .jar.bak or "
                                    + "renamed extension.",
                            "Octo's report names anything it found more than once.")),

            new Rule(List.of("glfw"),
                    "The game could not bring up a window or an OpenGL context. This is the graphics driver or "
                            + "the display, not the mods.",
                    List.of("Update the graphics driver, then try vanilla Minecraft once to confirm.",
                            "On a laptop with two GPUs, make sure Java is running on the discrete one.")),

            new Rule(List.of("failed to create window"),
                    "The game could not open its window. This is the graphics driver or the display, not the "
                            + "mods.",
                    List.of("Update the graphics driver, then try vanilla Minecraft once to confirm.")),

            new Rule(List.of("nullpointerexception"),
                    "Something the game expected to exist was missing at the moment it was used. The line in "
                            + "the message names what was null, and the first Minecraft or mod class under it is "
                            + "where to look.",
                    List.of("If a mod's package appears anywhere in the stack, start by removing that mod.",
                            SAFE_MODE)));

    private static final CrashReading UNRECOGNISED = new CrashReading(
            "Octo does not recognise this failure, so it will not guess at the cause. The stack below is the "
                    + "whole of what Minecraft reported.",
            List.of(SAFE_MODE,
                    "The Octo log holds everything the loader did before this, including which mods it changed "
                            + "to run here."));

    /** Reads a crash — a stack trace, a crash report, or both. */
    public static CrashReading of(String crashText) {
        if (crashText == null || crashText.isBlank()) {
            return UNRECOGNISED;
        }

        String text = crashText.toLowerCase(Locale.ROOT);

        for (Rule rule : RULES) {
            if (rule.matches(text)) {
                return new CrashReading(rule.meaning(), rule.steps());
            }
        }

        return UNRECOGNISED;
    }

    /** Whether this is the fallback, for callers that want to say "unrecognised". */
    public boolean isRecognised() {
        return !meaning().equals(UNRECOGNISED.meaning());
    }
}
