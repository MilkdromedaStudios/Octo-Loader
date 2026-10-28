package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraft.octotest.Recipe3;

/** A mod's recipe from before the game's recipe interface gained the methods it asks every recipe for now. */
class AbstractGapsTest {
    private static final String RECIPE = "mod/OldRecipe";
    private static final String GAME = "net/minecraft/octotest/Recipe3";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(AbstractGapsTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static byte[] resource(String internalName) {
        try (InputStream in = AbstractGapsTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** Implements the interface as it was: assemble with the registries, and getGroup. */
    private static byte[] oldRecipe() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, RECIPE, null, "java/lang/Object", new String[] { GAME });
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor assemble = writer.visitMethod(Opcodes.ACC_PUBLIC, "assemble",
                "(Ljava/lang/String;Lnet/minecraft/core/HolderLookup$Provider;)Ljava/lang/String;", null, null);
        assemble.visitCode();
        assemble.visitVarInsn(Opcodes.ALOAD, 1);
        assemble.visitLdcInsn(" pressed");
        assemble.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        assemble.visitInsn(Opcodes.ARETURN);
        assemble.visitMaxs(0, 0);
        assemble.visitEnd();

        MethodVisitor group = writer.visitMethod(Opcodes.ACC_PUBLIC, "getGroup", "()Ljava/lang/String;", null, null);
        group.visitCode();
        group.visitLdcInsn("machines");
        group.visitInsn(Opcodes.ARETURN);
        group.visitMaxs(0, 0);
        group.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("each method the game made abstract is answered: wider overload, old name, or a default")
    void missingMethodsAreAnswered() throws Exception {
        byte[] bytes = new AbstractGaps(new MemberIndex(AbstractGapsTest::resource))
                .transform(RECIPE, oldRecipe(), TransformContext.of(null, name -> true));
        Recipe3 recipe = (Recipe3) new Defining().define(RECIPE, bytes).getConstructor().newInstance();

        assertEquals("iron pressed", recipe.assemble("iron"), "assemble(input) reaches assemble(input, registries)");
        assertEquals("machines", recipe.group(), "group() reaches getGroup()");
        assertFalse(recipe.showNotification());
        assertEquals(0, recipe.count());
    }
}
