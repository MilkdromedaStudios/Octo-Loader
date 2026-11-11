package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;

import studios.milkdromeda.octo.client.feature.Gameplay;

/** Chat timestamps: each message, as it is added, with the time it came. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component octo$timestamp(Component message) {
        return Gameplay.stamped(message);
    }
}
