package studios.milkdromeda.octo.runtime;

/**
 * The load sequence, unified across four ecosystems.
 *
 * <p>Each loader named these differently and ran them at slightly different
 * moments. Octo defines one order and maps every ecosystem's events onto it,
 * which is what makes a Fabric mod and a Forge mod able to observe the same
 * startup in the same JVM.
 */
public enum Lifecycle {
    /** Before the game's own classes are loaded. Fabric {@code preLaunch}. */
    PRE_LAUNCH,

    /** Mod objects are created. Fabric {@code main}, Forge {@code @Mod} constructor. */
    CONSTRUCT,

    /** Registration and setup shared by both sides. Forge {@code FMLCommonSetupEvent}. */
    COMMON_SETUP,

    /** Side-specific setup. Fabric {@code client}/{@code server} entrypoints. */
    SIDED_SETUP,

    /** Mods may talk to each other. Forge {@code InterModEnqueueEvent}. */
    INTER_MOD,

    /** Everything is loaded. Forge {@code FMLLoadCompleteEvent}. */
    LOAD_COMPLETE
}
