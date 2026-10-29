package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Old rendering code given what 26.x's renderer asks of it: a block entity
 * renderer that still draws through {@code render(..., MultiBufferSource,
 * light, overlay)}, and the block renderer added back from 1.21.1, whose
 * methods 26.x's models answer.
 */
class RenderBridgesTest {
    private static final String RENDERER = "net/minecraft/client/renderer/blockentity/BlockEntityRenderer";
    private static final String OLD = "(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V";
    private static final String SUBMIT = "(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
    private static final String DISPATCHER = "net/minecraft/client/renderer/block/BlockRenderDispatcher";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(RenderBridgesTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Set<String> methods(ClassNode node) {
        return node.methods.stream().map(method -> method.name + method.desc).collect(Collectors.toSet());
    }

    /** What the JVM's verifier checks without loading other classes: every frame consistent. */
    private static void verifies(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) == 0) {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            }
        }
    }

    private static MethodVisitor constructor(ClassWriter writer) {
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        return init;
    }

    /** A renderer as a 1.21.1 mod has it, drawing under {@code name}. */
    private static byte[] oldRenderer(String name, String drawName, String... interfaces) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", interfaces);
        constructor(writer);
        MethodVisitor draw = writer.visitMethod(Opcodes.ACC_PUBLIC, drawName, OLD, null, null);
        draw.visitCode();
        draw.visitInsn(Opcodes.RETURN);
        draw.visitMaxs(0, 0);
        draw.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("an old block entity renderer gains the render-state methods, drawn by its old method")
    void oldRendererIsBridged() throws Exception {
        byte[] bytes = new LegacyRendererBridge().transform("mod/ShaftRenderer",
                oldRenderer("mod/ShaftRenderer", "render", RENDERER), TransformContext.of(null, name -> true));
        ClassNode bridged = read(bytes);
        Set<String> declared = methods(bridged);

        assertTrue(bridged.interfaces.contains("studios/milkdromeda/octo/bridge/client/LegacyRenderer"));
        assertTrue(declared.contains("submit" + SUBMIT));
        assertTrue(declared.contains("createRenderState"
                + "()Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"));
        assertTrue(declared.stream().anyMatch(method -> method.startsWith("extractRenderState(")));
        assertTrue(declared.contains("render" + OLD), "the old method stays, for the bridge and for subclasses");

        MethodNode bridge = bridged.methods.stream().filter(method -> method.name.equals("octo$render"))
                .findFirst().orElseThrow();
        boolean callsOld = false;

        for (AbstractInsnNode insn : bridge.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("mod/ShaftRenderer")
                    && call.name.equals("render") && call.desc.equals(OLD)) {
                callsOld = true;
            }
        }

        assertTrue(callsOld, "the bridge draws through the renderer's own method");
        verifies(bridged);
    }

    @Test
    @DisplayName("a renderer rebuilt with its draw method renamed submit is bridged the same way")
    void renamedDrawIsBridged() {
        ClassNode bridged = read(new LegacyRendererBridge().transform("mod/PressRenderer",
                oldRenderer("mod/PressRenderer", "submit", RENDERER), TransformContext.of(null, name -> true)));

        assertTrue(methods(bridged).contains("submit" + SUBMIT));
        assertTrue(methods(bridged).contains("submit" + OLD));
    }

    @Test
    @DisplayName("renderers written for render states, and classes that are not renderers, are left alone")
    void othersAreUntouched() {
        LegacyRendererBridge bridge = new LegacyRendererBridge();
        TransformContext context = TransformContext.of(null, name -> true);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/NewRenderer", null, "java/lang/Object",
                new String[] { RENDERER });
        constructor(writer);
        MethodVisitor submit = writer.visitMethod(Opcodes.ACC_PUBLIC, "submit", SUBMIT, null, null);
        submit.visitCode();
        submit.visitInsn(Opcodes.RETURN);
        submit.visitMaxs(0, 0);
        submit.visitEnd();
        writer.visitEnd();
        byte[] modern = writer.toByteArray();

        byte[] notRenderer = oldRenderer("mod/Helper", "render");

        assertSame(modern, bridge.transform("mod/NewRenderer", modern, context));
        assertSame(notRenderer, bridge.transform("mod/Helper", notRenderer, context));
    }

    @Test
    @DisplayName("the hierarchy check AbstractGaps uses agrees with what the bridge writes")
    void abstractGapsSeesTheBridge() {
        assertTrue(LegacyRendererBridge.bridges(List.of(RENDERER), List.of("render" + OLD)));
        assertFalse(LegacyRendererBridge.bridges(List.of(RENDERER), List.of("render" + OLD, "submit" + SUBMIT)));
        assertFalse(LegacyRendererBridge.bridges(List.of(), List.of("render" + OLD)));
        assertTrue(LegacyRendererBridge.writes("submit" + SUBMIT));
        assertFalse(LegacyRendererBridge.writes("render" + OLD));
    }

    /** The block renderer as 1.21.1 had it, cut down to one method of each return kind. */
    private static byte[] oldDispatcher() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, DISPATCHER, null, "java/lang/Object", null);
        constructor(writer);

        String[][] shapes = {
                { "renderSingleBlock", "(Ljava/lang/Object;JDI)V" },
                { "isFancy", "()Z" },
                { "light", "(I)I" },
                { "seed", "()J" },
                { "shade", "(F)F" },
                { "scale", "()D" },
                { "getBlockModel", "(Ljava/lang/Object;)Ljava/lang/String;" },
                { "quads", "()[Ljava/lang/Object;" },
        };

        for (String[] shape : shapes) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, shape[0], shape[1], null, null);
            method.visitCode();
            // The old body reaches for something 26.x no longer has.
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/renderer/block/BlockModelShaper",
                    "gone", "()V", false);
            method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
            method.visitInsn(Opcodes.ATHROW);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        MethodVisitor statics = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "forState",
                "(Ljava/lang/Object;Z)Ljava/lang/Object;", null, null);
        statics.visitCode();
        statics.visitInsn(Opcodes.ACONST_NULL);
        statics.visitInsn(Opcodes.ATHROW);
        statics.visitMaxs(0, 0);
        statics.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("each method of the added-back block renderer hands its arguments to 26.x's models")
    void restoredRendererIsAnswered() throws Exception {
        byte[] bytes = RestoredBridges.transform(DISPATCHER, oldDispatcher());
        ClassNode answered = read(bytes);
        verifies(answered);

        for (MethodNode method : answered.methods) {
            if (method.name.equals("<init>")) {
                continue;
            }

            boolean handsOver = false;

            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.equals("studios/milkdromeda/octo/bridge/client/LegacyModels")) {
                    handsOver = true;
                }
            }

            assertTrue(handsOver, method.name + " is answered by the helper");
        }

        // With no game to answer them, each comes back with its type's default
        // rather than the old body's failure, and the JVM accepts the class.
        Class<?> type = new Defining().define(DISPATCHER, bytes);
        Object dispatcher = type.getConstructor().newInstance();
        Method single = type.getMethod("renderSingleBlock", Object.class, long.class, double.class, int.class);
        single.invoke(dispatcher, "state", 3L, 1.5d, 7);

        assertEquals(false, type.getMethod("isFancy").invoke(dispatcher));
        assertEquals(0, type.getMethod("light", int.class).invoke(dispatcher, 15));
        assertEquals(0L, type.getMethod("seed").invoke(dispatcher));
        assertEquals(0f, type.getMethod("shade", float.class).invoke(dispatcher, 0.5f));
        assertEquals(0d, type.getMethod("scale").invoke(dispatcher));
        assertNull(type.getMethod("getBlockModel", Object.class).invoke(dispatcher, "state"));
        assertNull(type.getMethod("forState", Object.class, boolean.class).invoke(null, "state", true));
        assertNotNull(type.getMethod("quads"));
    }

    @Test
    @DisplayName("classes other than the block renderer family keep their own code")
    void othersKeepTheirCode() {
        byte[] other = oldRenderer("net/minecraft/client/renderer/Other", "render");

        assertFalse(RestoredBridges.bridges("net/minecraft/client/renderer/Other"));
        assertSame(other, RestoredBridges.transform("net/minecraft/client/renderer/Other", other));
    }
}
