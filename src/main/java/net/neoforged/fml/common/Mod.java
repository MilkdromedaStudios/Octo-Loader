package net.neoforged.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Octo Loader shim of NeoForge's entry annotation. Translated NeoForge mods
 * resolve this class instead of the real one; Octo's NeoForge runtime looks it
 * up reflectively to find and construct mod entry classes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();

    Dist[] dist() default {Dist.CLIENT, Dist.DEDICATED_SERVER};

    enum Dist {
        CLIENT,
        DEDICATED_SERVER
    }
}
