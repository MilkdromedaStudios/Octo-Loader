package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** A mod's override of a method the game has since made final, as Create's BlazeBurnerBlockItem has. */
class FinalOverridesTest {
    private static byte[] type(String name, String superName, int methodAccess, String answer) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor describe = writer.visitMethod(methodAccess, "getDescriptionId", "()Ljava/lang/String;", null,
                null);
        describe.visitCode();
        describe.visitLdcInsn(answer);
        describe.visitInsn(Opcodes.ARETURN);
        describe.visitMaxs(0, 0);
        describe.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("an override of a now-final method is renamed, so the class loads and the game's method answers")
    void overrideStepsAside() throws Exception {
        byte[] item = type("game/Item", "java/lang/Object", Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "item.game");
        byte[] modItem = type("mod/BlazeBurnerItem", "game/Item", Opcodes.ACC_PUBLIC, "item.mod");
        Map<String, byte[]> classes = Map.of("game/Item", item, "mod/BlazeBurnerItem", modItem);

        byte[] fixed = new FinalOverrides(new MemberIndex(classes::get)).transform("mod/BlazeBurnerItem", modItem,
                TransformContext.of(null, name -> true));

        ClassLoader loader = new ClassLoader(FinalOverridesTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = name.equals("game.Item") ? item : name.equals("mod.BlazeBurnerItem") ? fixed : null;

                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }

                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Class<?> type = loader.loadClass("mod.BlazeBurnerItem");
        Object instance = type.getConstructor().newInstance();

        assertEquals("item.game", type.getMethod("getDescriptionId").invoke(instance));
        assertNotNull(type.getDeclaredMethod(FinalOverrides.PREFIX + "getDescriptionId"));
    }
}
