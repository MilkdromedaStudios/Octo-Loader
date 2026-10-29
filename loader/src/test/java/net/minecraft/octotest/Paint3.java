package net.minecraft.octotest;

/**
 * Stands in for 26.x's {@code RenderType} or {@code IntProvider}: a type
 * whose static factories and constants moved to a plural holder,
 * {@link Paint3s}, the way {@code RenderType.entitySolid} became
 * {@code RenderTypes.entitySolid}.
 */
public final class Paint3 {
    public final String colour;

    Paint3(String colour) {
        this.colour = colour;
    }
}
