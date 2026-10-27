package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.neoforged.fml.common.asm.enumextension.ExtensionInfo;

/** NeoForge asks the game's enums whether mods extended them; under Octo none were. */
class ExtensibleEnumsTest {
    private static final String MODE = "net/minecraft/octotest/Mode3";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(ExtensibleEnumsTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    @Test
    @DisplayName("a game enum answers getExtensionInfo(): not extended, with all its constants vanilla")
    void enumAnswers() throws Exception {
        byte[] bytes;

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MODE + ".class")) {
            bytes = in.readAllBytes();
        }

        byte[] patched = new ExtensibleEnums().transform(MODE, bytes, TransformContext.of(null, name -> true));
        Class<?> mode = new Defining().define(MODE, patched);

        assertEquals(new ExtensionInfo(false, 2, 2), mode.getMethod("getExtensionInfo").invoke(null));
    }
}
