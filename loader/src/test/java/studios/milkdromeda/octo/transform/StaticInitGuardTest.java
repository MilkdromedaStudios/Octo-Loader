package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** A registration class whose middle entry this version cannot build, as Create's data components have. */
class StaticInitGuardTest {
    private static byte[] registrations() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/AllComponents", null, "java/lang/Object", null);

        for (String field : new String[] { "FIRST", "BROKEN", "LAST" }) {
            FieldVisitor visitor = writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, field,
                    "Ljava/lang/String;", null, null);
            visitor.visitEnd();
        }

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn("first");
        init.visitFieldInsn(Opcodes.PUTSTATIC, "mod/AllComponents", "FIRST", "Ljava/lang/String;");
        // A codec that came out null, used one call later.
        init.visitInsn(Opcodes.ACONST_NULL);
        init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, "mod/AllComponents", "BROKEN", "Ljava/lang/String;");
        init.visitLdcInsn("last");
        init.visitFieldInsn(Opcodes.PUTSTATIC, "mod/AllComponents", "LAST", "Ljava/lang/String;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("one failing static field leaves that field unset and the rest of the class initialised")
    void failingFieldsAreSkipped() throws Exception {
        byte[] guarded = new StaticInitGuard().transform("mod/AllComponents", registrations(),
                TransformContext.of(null, name -> true));
        Class<?> type = new ClassLoader(StaticInitGuardTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass("mod.AllComponents", guarded, 0, guarded.length);
            }
        }.define();

        assertEquals("first", type.getField("FIRST").get(null));
        assertNull(type.getField("BROKEN").get(null));
        assertEquals("last", type.getField("LAST").get(null));
    }
}
