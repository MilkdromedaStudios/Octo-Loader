package studios.milkdromeda.octo.bridge;

/**
 * The largest shaped recipe pattern this game reads, raised by
 * {@code ShapedRecipePattern.setCraftingSize(width, height)}, which
 * NeoForge's patch adds and mods call: Create's mechanical crafter takes
 * patterns up to 9×9, and every one of them failed at "too many rows, 3 is
 * maximum" without it ({@code studios.milkdromeda.octo.transform.CraftingSizeHook}).
 */
public final class CraftingSize {
    private static volatile int width = 3;
    private static volatile int height = 3;

    private CraftingSize() {
    }

    /** Only ever raised, as NeoForge's own is: one mod asking for less does not shrink another's. */
    public static synchronized void set(int newWidth, int newHeight) {
        width = Math.max(width, newWidth);
        height = Math.max(height, newHeight);
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }
}
