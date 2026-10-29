package net.minecraft.client.resources.model.geometry;

import com.mojang.blaze3d.platform.Transparency;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;

/** Test fixture: 26.x's baked quad, a record of corners, packed texture coordinates, a face and a material. */
public record BakedQuad(Vector3fc position0, Vector3fc position1, Vector3fc position2, Vector3fc position3,
        long packedUV0, long packedUV1, long packedUV2, long packedUV3, Direction direction,
        MaterialInfo materialInfo) {
    public Vector3fc position(int corner) {
        return switch (corner) {
            case 0 -> position0;
            case 1 -> position1;
            case 2 -> position2;
            default -> position3;
        };
    }

    public long packedUV(int corner) {
        return switch (corner) {
            case 0 -> packedUV0;
            case 1 -> packedUV1;
            case 2 -> packedUV2;
            default -> packedUV3;
        };
    }

    public record MaterialInfo(TextureAtlasSprite sprite, Transparency transparency, int tintIndex,
            Direction shadeDirectionOverride, int lightEmission) {
        public static MaterialInfo of(Material.Baked material, Transparency transparency, int tintIndex,
                Direction shadeDirectionOverride, int lightEmission) {
            return new MaterialInfo(material.sprite(), transparency, tintIndex, shadeDirectionOverride, lightEmission);
        }
    }
}
