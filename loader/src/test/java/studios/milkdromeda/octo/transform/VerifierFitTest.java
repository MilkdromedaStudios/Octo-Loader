package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A translated class handing a value to a class type that, in this game, is no
 * longer its supertype: Create returning a SimpleCraftingRecipeSerializer as a
 * RecipeSerializer.
 */
class VerifierFitTest {
    private static byte[] plain(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        if (name.equals("game/Serializer")) {
            MethodVisitor take = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "take",
                    "(Lgame/Serializer;I)I", null, null);
            take.visitCode();
            take.visitVarInsn(Opcodes.ILOAD, 1);
            take.visitInsn(Opcodes.IRETURN);
            take.visitMaxs(0, 0);
            take.visitEnd();
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Written with frames as javac would have, when Simple was still a Serializer. */
    private static byte[] recipes() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Recipes", null, "java/lang/Object", null);

        MethodVisitor serializer = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "serializer",
                "()Lgame/Serializer;", null, null);
        serializer.visitCode();
        serializer.visitTypeInsn(Opcodes.NEW, "game/Simple");
        serializer.visitInsn(Opcodes.DUP);
        serializer.visitMethodInsn(Opcodes.INVOKESPECIAL, "game/Simple", "<init>", "()V", false);
        serializer.visitInsn(Opcodes.ARETURN);
        serializer.visitMaxs(0, 0);
        serializer.visitEnd();

        MethodVisitor count = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "count", "()I", null,
                null);
        count.visitCode();
        count.visitTypeInsn(Opcodes.NEW, "game/Simple");
        count.visitInsn(Opcodes.DUP);
        count.visitMethodInsn(Opcodes.INVOKESPECIAL, "game/Simple", "<init>", "()V", false);
        count.visitInsn(Opcodes.ICONST_3);
        count.visitMethodInsn(Opcodes.INVOKESTATIC, "game/Serializer", "take", "(Lgame/Serializer;I)I", false);
        count.visitInsn(Opcodes.IRETURN);
        count.visitMaxs(0, 0);
        count.visitEnd();

        MethodVisitor fine = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fine", "()I", null, null);
        fine.visitCode();
        fine.visitInsn(Opcodes.ICONST_5);
        fine.visitInsn(Opcodes.IRETURN);
        fine.visitMaxs(0, 0);
        fine.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("a value that is no longer a subtype is cast where it is handed on, so the class verifies")
    void misfitsAreCast() throws Exception {
        Map<String, byte[]> game = Map.of("game/Serializer", plain("game/Serializer"), "game/Simple",
                plain("game/Simple"));
        byte[] fitted = new VerifierFit(new MemberIndex(name -> game.get(name) != null ? game.get(name)
                : name.equals("mod/Recipes") ? recipes() : null), true)
                .transform("mod/Recipes", recipes(), TransformContext.of(null, name -> true));

        ClassLoader loader = new ClassLoader(VerifierFitTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String internal = name.replace('.', '/');
                byte[] bytes = internal.equals("mod/Recipes") ? fitted : game.get(internal);

                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }

                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Class<?> recipes = loader.loadClass("mod.Recipes");

        assertEquals(5, recipes.getMethod("fine").invoke(null), "the rest of the class runs");
        InvocationTargetException returned = assertThrows(InvocationTargetException.class,
                () -> recipes.getMethod("serializer").invoke(null));
        assertEquals(ClassCastException.class, returned.getCause().getClass());
        InvocationTargetException passed = assertThrows(InvocationTargetException.class,
                () -> recipes.getMethod("count").invoke(null));
        assertEquals(ClassCastException.class, passed.getCause().getClass());
    }
}
