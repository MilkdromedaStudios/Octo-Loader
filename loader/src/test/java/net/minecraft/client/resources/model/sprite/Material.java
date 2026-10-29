package net.minecraft.client.resources.model.sprite;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Test fixture: 26.x's material, for its baked form. */
public final class Material {
    private Material() {
    }

    public record Baked(TextureAtlasSprite sprite, boolean forceTranslucent) {
    }
}
