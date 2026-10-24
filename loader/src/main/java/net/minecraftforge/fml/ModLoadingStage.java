package net.minecraftforge.fml;

/**
 * The stages Forge walks each mod through, by name. Mods compare against these
 * rather than act on them — {@code ERROR} most of all, to tell whether loading
 * failed — so the constants are what matters; the per-stage work queues behind
 * Forge's own version have no counterpart here.
 */
public enum ModLoadingStage {
    ERROR,
    VALIDATE,
    CONSTRUCT,
    COMMON_SETUP,
    SIDED_SETUP,
    ENQUEUE_IMC,
    PROCESS_IMC,
    COMPLETE,
    DONE
}
