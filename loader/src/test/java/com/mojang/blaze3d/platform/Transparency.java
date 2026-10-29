package com.mojang.blaze3d.platform;

/** Test fixture: 26.x's transparency of a material. */
public record Transparency(boolean hasTransparent, boolean hasTranslucent) {
    public static final Transparency NONE = new Transparency(false, false);
    public static final Transparency TRANSPARENT = new Transparency(true, false);
}
