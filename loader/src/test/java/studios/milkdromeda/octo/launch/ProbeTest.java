package studios.milkdromeda.octo.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import studios.milkdromeda.octo.report.LoadingReport;
import studios.milkdromeda.octo.testkit.JarBuilder;
import studios.milkdromeda.octo.transform.PhantomClasses;
import studios.milkdromeda.octo.transform.ProbeRewriter;
import studios.milkdromeda.octo.transform.TransformPipeline;

/**
 * A mod asking whether another mod is installed, against a class Octo can
 * stand in for.
 */
class ProbeTest {
    @TempDir
    Path directory;

    /** {@code probe()} is {@code Class.forName} in a try; {@code link()} names the class in its code. */
    private static byte[] prober() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Prober", null, "java/lang/Object", null);

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()Z", null, null);
        probe.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label missing = new Label();
        probe.visitTryCatchBlock(start, end, missing, "java/lang/ClassNotFoundException");
        probe.visitLabel(start);
        probe.visitLdcInsn("othermod.WorldMap");
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        probe.visitInsn(Opcodes.POP);
        probe.visitInsn(Opcodes.ICONST_1);
        probe.visitLabel(end);
        probe.visitInsn(Opcodes.IRETURN);
        probe.visitLabel(missing);
        probe.visitInsn(Opcodes.POP);
        probe.visitInsn(Opcodes.ICONST_0);
        probe.visitInsn(Opcodes.IRETURN);
        probe.visitMaxs(0, 0);
        probe.visitEnd();

        MethodVisitor link = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "link",
                "()Ljava/lang/Object;", null, null);
        link.visitCode();
        link.visitTypeInsn(Opcodes.NEW, "othermod/WorldMap");
        link.visitInsn(Opcodes.DUP);
        link.visitMethodInsn(Opcodes.INVOKESPECIAL, "othermod/WorldMap", "<init>", "()V", false);
        link.visitInsn(Opcodes.ARETURN);
        link.visitMaxs(0, 0);
        link.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("Class.forName from a mod finds no stand-in, while code that names the class still links")
    void probesAreAnsweredHonestly() throws Exception {
        Path jar = new JarBuilder().classes(Map.of("mod/Prober", prober())).writeTo(directory.resolve("mod.jar"));
        PhantomClasses phantoms = new PhantomClasses();
        phantoms.declareClass("othermod/WorldMap");

        try (OctoClassLoader loader = new OctoClassLoader(List.of(jar.toUri().toURL()),
                ProbeTest.class.getClassLoader(), TransformPipeline.empty(), phantoms,
                LaunchContext.builder(directory).build().compat(), new LoadingReport())) {
            loader.addGlobalTransformer(new ProbeRewriter());
            Class<?> prober = loader.loadClass("mod.Prober");
            Method probe = prober.getMethod("probe");

            assertEquals(false, probe.invoke(null), "World Map is not installed, and the probe must say so");
            assertNotNull(prober.getMethod("link").invoke(null), "code that names the class gets the stand-in");
            assertEquals(false, probe.invoke(null), "and the stand-in, once made, is still no answer to a probe");
        }
    }

}
