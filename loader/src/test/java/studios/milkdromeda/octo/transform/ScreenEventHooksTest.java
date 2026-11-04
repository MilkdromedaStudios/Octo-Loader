package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import studios.milkdromeda.octo.bridge.forge.NeoMenus;
import studios.milkdromeda.octo.bridge.forge.NeoScreenEvents;

/** Every call the screen and menu hooks write goes to a bridge method that is there, with that descriptor. */
class ScreenEventHooksTest {
    private static Method find(Class<?> owner, String name, String descriptor) {
        return Arrays.stream(owner.getMethods())
                .filter(method -> method.getName().equals(name) && Type.getMethodDescriptor(method).equals(descriptor))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("each screen call becomes a bridge call with the screen first and every object as Object")
    void bridgedDescriptors() {
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;Z)Z",
                ScreenEventHooks.bridged("(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"));
        assertEquals("(Ljava/lang/Object;DDDD)Z", ScreenEventHooks.bridged("(DDDD)Z"));
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;IIF)V",
                ScreenEventHooks.bridged("(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"));
    }

    @Test
    @DisplayName("the bridge has every method the hooks call")
    void bridgeMethodsExist() {
        String[][] calls = {
                { "mouseClicked", "(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z" },
                { "mouseReleased", "(Lnet/minecraft/client/input/MouseButtonEvent;)Z" },
                { "mouseDragged", "(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z" },
                { "mouseScrolled", "(DDDD)Z" },
                { "keyPressed", "(Lnet/minecraft/client/input/KeyEvent;)Z" },
                { "keyReleased", "(Lnet/minecraft/client/input/KeyEvent;)Z" },
                { "charTyped", "(Lnet/minecraft/client/input/CharacterEvent;)Z" },
                { "extract", "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V" } };

        for (String[] call : calls) {
            assertNotNull(find(NeoScreenEvents.class, call[0], ScreenEventHooks.bridged(call[1])), call[0]);
        }

        assertNotNull(find(NeoScreenEvents.class, "init", "(Ljava/lang/Object;)V"));
        assertNotNull(find(NeoScreenEvents.class, "opening", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
        assertNotNull(find(NeoMenus.class, "open",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/OptionalInt;"));
        assertNotNull(find(NeoMenus.class, "openAt",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/OptionalInt;"));
        assertNotNull(find(NeoMenus.class, "create", "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
    }
}
