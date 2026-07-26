package studios.milkdromeda.octo.transform;

import java.util.function.Predicate;

import studios.milkdromeda.octo.runtime.LoadedMod;

/**
 * What a transformer is allowed to know.
 *
 * @param mod            the mod the class belongs to, or {@code null} for a game class
 * @param classExists    whether an internal class name resolves in the running JVM
 */
public record TransformContext(LoadedMod mod, Predicate<String> classExists) {
    public static TransformContext of(LoadedMod mod, Predicate<String> classExists) {
        return new TransformContext(mod, classExists);
    }

    public void note(String fix) {
        if (mod != null) {
            mod.recordFix(fix);
        }
    }

    public String modId() {
        return mod == null ? "minecraft" : mod.id();
    }
}
