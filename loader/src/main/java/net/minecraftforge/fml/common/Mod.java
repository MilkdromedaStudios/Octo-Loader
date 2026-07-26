package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import net.minecraftforge.api.distmarker.Dist;

/**
 * Marks the class Forge constructs to start a mod.
 *
 * <p>Octo declares the union of the shapes this annotation has had: {@code value}
 * from 1.13 onwards, {@code modid} from 1.7 through 1.12, and the nested
 * annotations mods of that era hung their lifecycle methods on. A mod compiled
 * against any of those versions finds what it expects.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value() default "";

    /** Used instead of {@link #value()} by mods from 1.7 through 1.12. */
    String modid() default "";

    String name() default "";

    String version() default "";

    String dependencies() default "";

    String acceptedMinecraftVersions() default "";

    boolean clientSideOnly() default false;

    boolean serverSideOnly() default false;

    /** The mod's singleton instance is injected here, 1.7 through 1.12. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Instance {
        String value() default "";
    }

    /** Marks a lifecycle callback taking one FML event, 1.7 through 1.12. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface EventHandler {
    }

    /** Registers a class of static listeners automatically. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface EventBusSubscriber {
        Dist[] value() default { Dist.CLIENT, Dist.DEDICATED_SERVER };

        String modid() default "";

        Bus bus() default Bus.FORGE;

        enum Bus { FORGE, MOD }
    }
}
