package net.neoforged.fml.common.asm.enumextension;

/**
 * Whether an enum of the game's was extended by mods, and by how many
 * constants: what FML's launcher adds to every enum NeoForge lets mods extend.
 *
 * <p>Octo extends no enum, so every one answers that it was not. NeoForge asks
 * {@code RecipeBookType} right after registration; without the answer, the
 * whole of NeoForge's work after registration — entity attributes, spawn
 * placements, the blocks of block entity types, creative tab order — was
 * skipped.
 */
public record ExtensionInfo(boolean extended, int vanillaCount, int totalCount) {
    public static <T extends Enum<T>> ExtensionInfo nonExtended(Class<T> type) {
        int count = type.getEnumConstants() == null ? 0 : type.getEnumConstants().length;
        return new ExtensionInfo(false, count, count);
    }
}
