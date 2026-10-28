package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** The game's sealed and final types, opened to the mods that extend them. */
class OpenTypesTest {
    private static byte[] type(String name, int access, String... permitted) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, access, name, null, "java/lang/Object", null);

        for (String subclass : permitted) {
            writer.visitPermittedSubclass(subclass);
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static final TransformContext GAME = TransformContext.of(null, name -> true);

    @Test
    @DisplayName("a sealed game type loses its list of permitted subclasses")
    void sealedTypesAreOpened() {
        byte[] holder = type("net/minecraft/core/Holder", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE
                | Opcodes.ACC_ABSTRACT, "net/minecraft/core/Holder$Direct", "net/minecraft/core/Holder$Reference");

        ClassNode opened = read(new OpenTypes(Set.of()).transform("net/minecraft/core/Holder", holder, GAME));

        assertNull(opened.permittedSubclasses, "Moonlight's RegSupplier implements it, and has to be definable");
        assertEquals(Opcodes.ACC_INTERFACE, opened.access & Opcodes.ACC_INTERFACE, "everything else stays");
    }

    @Test
    @DisplayName("a final game class is opened only when a mod extends it")
    void finalClassesAreOpenedForTheirSubclasses() {
        byte[] profile = type("com/mojang/authlib/GameProfile", Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL);
        byte[] other = type("com/mojang/authlib/Other", Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL);
        OpenTypes open = new OpenTypes(Set.of("com/mojang/authlib/GameProfile"));

        assertEquals(0, read(open.transform("com/mojang/authlib/GameProfile", profile, GAME)).access
                & Opcodes.ACC_FINAL, "Create's deployer subclasses the profile");
        assertTrue((read(open.transform("com/mojang/authlib/Other", other, GAME)).access & Opcodes.ACC_FINAL) != 0,
                "a final class nobody extends keeps it");
    }
}
