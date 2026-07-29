package studios.milkdromeda.octo.transform;

/**
 * Mixin, as the class loader sees it: a rewrite that also invents classes.
 *
 * <p>A mixin is free to use an inner class — an anonymous {@code Comparator}, a
 * captured lambda body, Fabric API's {@code ChiseledBookShelfBlockEntityMixin$1}
 * — and the code that refers to it ends up copied into a game class in another
 * package entirely. So mixin renames that inner class into the target's package,
 * hands the target a reference to the new name, and keeps the bytes to itself
 * until something asks. Nothing on the class path has them.
 *
 * <p>Which makes answering "no such class" a real failure rather than a missing
 * nicety: the game class the mixin touched dies with a
 * {@code NoClassDefFoundError} the first time it is used, and if that class is
 * one the game loads while filling its registries, the game is left half built
 * and crashes later somewhere that names none of this.
 */
public interface Weaver extends Transformer {
    /**
     * The class behind a name mixin invented, if the name is one of mixin's.
     *
     * @param className internal name, e.g.
     *        {@code net/minecraft/world/level/block/entity/ChiseledBookShelfBlockEntity$Anonymous$6b8f}
     * @return the class file, or {@code null} when mixin did not invent this name
     */
    byte[] generate(String className);
}
