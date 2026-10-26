package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** A class written when its superclass was a class, running where it is an interface: Create's ore feature. */
class SupertypeFitTest {
    /** Feature as 26.x has it: an interface with a default method. */
    private static byte[] feature() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "game/Feature",
                null, "java/lang/Object", null);
        MethodVisitor size = writer.visitMethod(Opcodes.ACC_PUBLIC, "size", "()I", null, null);
        size.visitCode();
        size.visitIntInsn(Opcodes.BIPUSH, 7);
        size.visitInsn(Opcodes.IRETURN);
        size.visitMaxs(0, 0);
        size.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** The mod's feature, compiled when Feature was a class with a Feature(int) constructor. */
    private static byte[] ore() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Ore", null, "game/Feature", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitInsn(Opcodes.ICONST_3);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "game/Feature", "<init>", "(I)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor measure = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "measure",
                "(Lgame/Feature;)I", null, null);
        measure.visitCode();
        measure.visitVarInsn(Opcodes.ALOAD, 0);
        measure.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "game/Feature", "size", "()I", false);
        measure.visitInsn(Opcodes.IRETURN);
        measure.visitMaxs(0, 0);
        measure.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("a superclass that became an interface is implemented, and calls on it take the interface's instruction")
    void classesBecomeInterfaces() throws Exception {
        Map<String, byte[]> game = Map.of("game/Feature", feature());
        byte[] fitted = new SupertypeFit(new MemberIndex(game::get), true).transform("mod/Ore", ore(),
                TransformContext.of(null, name -> true));

        ClassLoader loader = new ClassLoader(SupertypeFitTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = name.equals("mod.Ore") ? fitted : game.get(name.replace('.', '/'));

                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }

                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Class<?> ore = loader.loadClass("mod.Ore");
        Object instance = ore.getConstructor().newInstance();

        assertTrue(loader.loadClass("game.Feature").isInstance(instance));
        assertEquals(7, ore.getMethod("measure", loader.loadClass("game.Feature")).invoke(null, instance));
    }
}
