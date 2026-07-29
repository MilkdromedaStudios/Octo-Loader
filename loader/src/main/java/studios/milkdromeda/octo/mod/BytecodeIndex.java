package studios.milkdromeda.octo.mod;

import java.util.List;
import java.util.Map;

/**
 * What a mod's classes say about themselves, read once when the jar is scanned.
 *
 * <p>Forge and NeoForge mods do not write down the classes they are meant to
 * instantiate; they annotate them and expect the loader to have indexed every
 * class in every jar before the first mod is constructed. JEI finds its plugins
 * this way — {@code @JeiPlugin} — and so does every mod that offers a plugin
 * interface of its own. A loader that cannot answer leaves those mods running
 * with nothing registered, which is worse than not loading them: JEI without its
 * plugins is an empty screen and no error.
 *
 * <p>Kept as plain strings rather than the ASM and NeoForge types the mods
 * eventually see, because a mod folder is tens of thousands of classes and most
 * launches never get asked. The conversion happens on the first question.
 */
public record BytecodeIndex(List<ScannedClass> classes, List<ScannedAnnotation> annotations) {
    public static final BytecodeIndex EMPTY = new BytecodeIndex(List.of(), List.of());

    /**
     * A class, its parent and the interfaces it declared.
     *
     * @param internalName e.g. {@code mezz/jei/library/plugins/vanilla/VanillaPlugin}
     * @param superName    the parent's internal name, or {@code null} for {@code Object}
     */
    public record ScannedClass(String internalName, String superName, List<String> interfaces) {
    }

    /**
     * One annotation, and the members it was written with.
     *
     * <p>The naming follows what the mods expect, because it is handed straight to
     * them: {@code member} is the class's own binary name when the annotation is on
     * a class, the field's name when it is on a field, and {@code name + descriptor}
     * when it is on a method — an annotation on one overload is not on the others.
     *
     * @param descriptor the annotation type's descriptor, e.g. {@code Lmezz/jei/api/JeiPlugin;}
     * @param className  the internal name of the class the annotation was found in
     */
    public record ScannedAnnotation(String descriptor, Target target, String className, String member,
            Map<String, Object> values) {
        /** Where the annotation was written. */
        public enum Target {
            TYPE, FIELD, METHOD
        }
    }
}
