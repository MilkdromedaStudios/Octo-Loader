package net.minecraft.client.renderer.entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The item renderer mods drew items with until 26.x, drawing them the way the
 * game does now.
 *
 * <p>26.x has no item renderer: an item is resolved into an
 * {@link ItemStackRenderState} and submitted with the rest of the frame. A
 * mod's block entity renderer — Create's depot, basin and belt, every item
 * they hold — asked {@code Minecraft.getItemRenderer()} for this and was
 * handed nothing; the basin's renderer failed part way, and the frame with it.
 *
 * <p>Here {@code renderStatic} resolves the item and submits it at the pose
 * the mod has moved to, into the collector of the frame Octo is drawing the
 * old renderer for. Outside such a frame there is nothing to submit into, and
 * the item is left out.
 */
public class ItemRenderer {
    private static final ItemRenderer INSTANCE = new ItemRenderer();
    private static volatile MethodHandle collector;

    public static ItemRenderer octo$instance() {
        return INSTANCE;
    }

    public void renderStatic(ItemStack stack, ItemDisplayContext context, int light, int overlay, PoseStack poseStack,
            MultiBufferSource buffers, Level level, int seed) {
        draw(null, stack, context, poseStack, level, light, overlay, seed);
    }

    public void renderStatic(LivingEntity entity, ItemStack stack, ItemDisplayContext context, boolean leftHand,
            PoseStack poseStack, MultiBufferSource buffers, Level level, int light, int overlay, int seed) {
        draw(entity, stack, context, poseStack, level, light, overlay, seed);
    }

    private static void draw(ItemOwner owner, ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
            Level level, int light, int overlay, int seed) {
        if (stack == null || stack.isEmpty() || poseStack == null) {
            return;
        }

        SubmitNodeCollector into = current();

        if (into == null) {
            return;
        }

        ItemStackRenderState state = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(state, stack, context, level, owner, seed);
        state.submit(poseStack, into, light, overlay, 0);
    }

    /** The collector of the old renderer Octo is drawing now, if it is drawing one. */
    private static SubmitNodeCollector current() {
        try {
            MethodHandle handle = collector;

            if (handle == null) {
                handle = MethodHandles.publicLookup().findStatic(
                        Class.forName("studios.milkdromeda.octo.bridge.client.LegacyRenderers"), "collector",
                        MethodType.methodType(Object.class));
                collector = handle;
            }

            return (SubmitNodeCollector) handle.invoke();
        } catch (Throwable e) {
            return null;
        }
    }
}
