package net.minecraftforge.api.distmarker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a member as present on one side only.
 *
 * <p>Octo keeps the annotation but does not strip the members it marks: the
 * class loader already refuses to load a side-only mod on the wrong side, and
 * stripping is what makes Forge's side-only errors so hard to read.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
public @interface OnlyIn {
    Dist value();
}
