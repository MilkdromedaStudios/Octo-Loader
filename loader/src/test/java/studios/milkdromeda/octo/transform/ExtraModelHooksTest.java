package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** The model reload's three points, each calling out with what the method holds. */
class ExtraModelHooksTest {
    private static final String PACKAGE = "net/minecraft/client/resources/model/";

    private static byte[] type(String name, String method, String descriptor, int returnOpcode) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, method, descriptor, null, null);
        visitor.visitCode();

        if (returnOpcode == Opcodes.ARETURN) {
            visitor.visitInsn(Opcodes.ACONST_NULL);
        }

        visitor.visitInsn(returnOpcode);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> hooks(byte[] bytes, String method) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> out = new ArrayList<>();

        for (MethodNode candidate : node.methods) {
            if (!candidate.name.equals(method)) {
                continue;
            }

            for (AbstractInsnNode insn : candidate.instructions) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.equals("studios/milkdromeda/octo/bridge/client/ExtraModels")) {
                    out.add(call.name);
                }
            }
        }

        return out;
    }

    @Test
    @DisplayName("discovery, baking and applying each call the extra models out")
    void eachPointIsHooked() {
        ExtraModelHooks hooks = new ExtraModelHooks();
        TransformContext context = TransformContext.of(null, name -> true);

        byte[] discovery = hooks.transform(PACKAGE + "ModelDiscovery",
                type(PACKAGE + "ModelDiscovery", "resolve", "()Ljava/util/Map;", Opcodes.ARETURN), context);
        byte[] bakery = hooks.transform(PACKAGE + "ModelBakery", type(PACKAGE + "ModelBakery", "bakeModels",
                "(L" + PACKAGE + "sprite/MaterialBaker;Ljava/util/concurrent/Executor;)"
                        + "Ljava/util/concurrent/CompletableFuture;", Opcodes.ARETURN), context);
        byte[] manager = hooks.transform(PACKAGE + "ModelManager", type(PACKAGE + "ModelManager", "apply",
                "(L" + PACKAGE + "ModelManager$ReloadState;)V", Opcodes.RETURN), context);

        assertEquals(List.of("discover"), hooks(discovery, "resolve"));
        assertEquals(List.of("baking"), hooks(bakery, "bakeModels"));
        assertEquals(List.of("applied"), hooks(manager, "apply"));
    }

    @Test
    @DisplayName("a method of another shape is left alone")
    void otherShapesAreUntouched() {
        byte[] other = type(PACKAGE + "ModelManager", "apply", "()V", Opcodes.RETURN);
        assertSame(other, new ExtraModelHooks().transform(PACKAGE + "ModelManager", other,
                TransformContext.of(null, name -> true)));
    }
}
