package studios.milkdromeda.octoloader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates small class files whose constant pools reference arbitrary (even
 * nonexistent) classes and members — exactly what API-replacement fixtures
 * need, since old-version APIs are not on the test classpath. The generated
 * code is never executed or verified, only parsed and rewritten.
 */
public final class TestClasses {
    private TestClasses() {
    }

    public static Builder cls(String internalName) {
        return new Builder(internalName);
    }

    public static final class Builder {
        private final String internalName;
        private String modAnnotationId;
        private final List<Runnable> instructions = new ArrayList<>();
        private MethodVisitor mv;

        private Builder(String internalName) {
            this.internalName = internalName;
        }

        public Builder modAnnotation(String id) {
            this.modAnnotationId = id;
            return this;
        }

        /** Adds a {@code NEW type} instruction, putting the type in the constant pool. */
        public Builder newInsn(String type) {
            instructions.add(() -> mv.visitTypeInsn(Opcodes.NEW, type));
            return this;
        }

        public Builder invokeInterface(String owner, String name, String desc) {
            instructions.add(() -> mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, name, desc, true));
            return this;
        }

        public Builder invokeVirtual(String owner, String name, String desc) {
            instructions.add(() -> mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, desc, false));
            return this;
        }

        public Builder getField(String owner, String name, String desc) {
            instructions.add(() -> mv.visitFieldInsn(Opcodes.GETFIELD, owner, name, desc));
            return this;
        }

        public byte[] bytes() {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
            if (modAnnotationId != null) {
                AnnotationVisitor av = cw.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
                av.visit("value", modAnnotationId);
                av.visitEnd();
            }
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "touch", "()V", null, null);
            mv.visitCode();
            for (Runnable insn : instructions) insn.run();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(8, 8);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }
    }
}
