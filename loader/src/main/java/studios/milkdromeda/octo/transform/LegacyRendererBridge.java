package studios.milkdromeda.octo.transform;

import java.util.Arrays;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Gives a block entity renderer from before 26.x what 26.x's renderer asks
 * of it, drawn by its old method ({@link studios.milkdromeda.octo.bridge.client.LegacyRenderers}).
 *
 * <p>A class that implements {@code BlockEntityRenderer} itself and still
 * draws through {@code render(blockEntity, partialTick, poseStack, buffers,
 * light, overlay)} — under that name, or {@code submit}, which is what the
 * rebuild calls it now — gets {@code createRenderState}, {@code
 * extractRenderState} and {@code submit} that hand its old method what it
 * needs, and the {@code LegacyRenderer} interface they call it through. Its
 * subclasses inherit all of it, and their own drawing code with it.
 */
public final class LegacyRendererBridge implements Transformer {
    private static final String RENDERER = "net/minecraft/client/renderer/blockentity/BlockEntityRenderer";
    private static final String LEGACY = "studios/milkdromeda/octo/bridge/client/LegacyRenderer";
    private static final String HELPER = "studios/milkdromeda/octo/bridge/client/LegacyRenderers";
    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final String STATE = "net/minecraft/client/renderer/blockentity/state/BlockEntityRenderState";
    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String BUFFERS = "net/minecraft/client/renderer/MultiBufferSource";
    private static final String VEC3 = "net/minecraft/world/phys/Vec3";
    private static final String CRUMBLING = "net/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay";

    /** The old drawing method, as it was declared until 1.21.4 and from 1.21.5 with the camera. */
    private static final String OLD = "(L" + BLOCK_ENTITY + ";FL" + POSE_STACK + ";L" + BUFFERS + ";II)V";
    private static final String OLD_WITH_CAMERA = "(L" + BLOCK_ENTITY + ";FL" + POSE_STACK + ";L" + BUFFERS
            + ";IIL" + VEC3 + ";)V";

    private static final String CREATE_STATE = "()L" + STATE + ";";
    private static final String EXTRACT = "(L" + BLOCK_ENTITY + ";L" + STATE + ";FL" + VEC3 + ";L" + CRUMBLING + ";)V";
    private static final String SUBMIT = "(L" + STATE + ";L" + POSE_STACK
            + ";Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
    private static final String BRIDGE = "(Ljava/lang/Object;FLjava/lang/Object;Ljava/lang/Object;IILjava/lang/Object;)V";

    // An entity renderer drawn the same way: render(entity, yaw, partialTick, poseStack, buffers, light).
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String ENTITY_STATE = "net/minecraft/client/renderer/entity/state/EntityRenderState";
    private static final String LEGACY_ENTITY = "studios/milkdromeda/octo/bridge/client/LegacyEntityRenderer";
    private static final String ENTITY_OLD = "(L" + ENTITY + ";FFL" + POSE_STACK + ";L" + BUFFERS + ";I)V";
    private static final String ENTITY_CREATE = "()L" + ENTITY_STATE + ";";
    private static final String ENTITY_EXTRACT = "(L" + ENTITY + ";L" + ENTITY_STATE + ";F)V";
    private static final String ENTITY_SUBMIT = "(L" + ENTITY_STATE + ";L" + POSE_STACK
            + ";Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
    private static final String ENTITY_BRIDGE = "(Ljava/lang/Object;FFLjava/lang/Object;Ljava/lang/Object;I)V";

    @Override
    public String name() {
        return "legacy-renderer-bridge";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);

        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) {
            return bytes;
        }

        if (!Arrays.asList(reader.getInterfaces()).contains(RENDERER)) {
            return bridgeEntityRenderer(reader, bytes, className);
        }

        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_CODE);
        MethodNode old = null;

        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0
                    && (method.name.equals("render") || method.name.equals("submit"))
                    && (method.desc.equals(OLD) || method.desc.equals(OLD_WITH_CAMERA))) {
                old = method;
            }

            if (method.name.equals("submit") && method.desc.equals(SUBMIT)
                    || method.name.equals("createRenderState") && method.desc.equals(CREATE_STATE)) {
                // Already written for render states: nothing to bridge.
                return bytes;
            }
        }

        if (old == null) {
            return bytes;
        }

        String drawName = old.name;
        String drawDesc = old.desc;
        boolean camera = drawDesc.equals(OLD_WITH_CAMERA);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
                String[] widened = Arrays.copyOf(interfaces, interfaces.length + 1);
                widened[interfaces.length] = LEGACY;
                super.visit(version, access, name, signature, superName, widened);
            }

            @Override
            public void visitEnd() {
                writeCreateState(cv);
                writeExtract(cv);
                writeSubmit(cv);
                writeBridge(cv, className, drawName, drawDesc, camera);
                super.visitEnd();
            }
        }, 0);

        return writer.toByteArray();
    }

    /**
     * An entity renderer that still draws through {@code render(entity, yaw,
     * partialTick, poseStack, buffers, light)}: given 26.x's render-state
     * methods, which hand that method what it needs. Create's contraptions —
     * everything a bearing, piston or gantry moves — were drawn by one, and
     * were simply not there.
     */
    private static byte[] bridgeEntityRenderer(ClassReader reader, byte[] bytes, String className) {
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_CODE);
        MethodNode old = null;

        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0
                    && (method.name.equals("render") || method.name.equals("submit"))
                    && method.desc.equals(ENTITY_OLD)) {
                old = method;
            }

            if (method.name.equals("createRenderState") && method.desc.equals(ENTITY_CREATE)
                    || method.name.equals("submit") && method.desc.equals(ENTITY_SUBMIT)) {
                return bytes;
            }
        }

        if (old == null || node.superName == null) {
            return bytes;
        }

        String drawName = old.name;
        String superName = node.superName;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String signature, String superClass,
                    String[] interfaces) {
                String[] widened = Arrays.copyOf(interfaces, interfaces.length + 1);
                widened[interfaces.length] = LEGACY_ENTITY;
                super.visit(version, access, name, signature, superClass, widened);
            }

            @Override
            public void visitEnd() {
                MethodVisitor create = cv.visitMethod(Opcodes.ACC_PUBLIC, "createRenderState", ENTITY_CREATE, null,
                        null);
                create.visitCode();
                create.visitVarInsn(Opcodes.ALOAD, 0);
                create.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "newEntityState",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                create.visitTypeInsn(Opcodes.CHECKCAST, ENTITY_STATE);
                create.visitInsn(Opcodes.ARETURN);
                create.visitMaxs(0, 0);
                create.visitEnd();

                // The game's own part first: position, light, name tag.
                MethodVisitor extract = cv.visitMethod(Opcodes.ACC_PUBLIC, "extractRenderState", ENTITY_EXTRACT,
                        null, null);
                extract.visitCode();
                extract.visitVarInsn(Opcodes.ALOAD, 0);
                extract.visitVarInsn(Opcodes.ALOAD, 1);
                extract.visitVarInsn(Opcodes.ALOAD, 2);
                extract.visitVarInsn(Opcodes.FLOAD, 3);
                extract.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "extractRenderState", ENTITY_EXTRACT,
                        false);
                extract.visitVarInsn(Opcodes.ALOAD, 2);
                extract.visitVarInsn(Opcodes.ALOAD, 1);
                extract.visitVarInsn(Opcodes.FLOAD, 3);
                extract.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "captureEntity",
                        "(Ljava/lang/Object;Ljava/lang/Object;F)V", false);
                extract.visitInsn(Opcodes.RETURN);
                extract.visitMaxs(0, 0);
                extract.visitEnd();

                MethodVisitor submit = cv.visitMethod(Opcodes.ACC_PUBLIC, "submit", ENTITY_SUBMIT, null, null);
                submit.visitCode();
                submit.visitVarInsn(Opcodes.ALOAD, 0);
                submit.visitVarInsn(Opcodes.ALOAD, 1);
                submit.visitVarInsn(Opcodes.ALOAD, 2);
                submit.visitVarInsn(Opcodes.ALOAD, 3);
                submit.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "submitEntity",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                submit.visitInsn(Opcodes.RETURN);
                submit.visitMaxs(0, 0);
                submit.visitEnd();

                MethodVisitor bridge = cv.visitMethod(Opcodes.ACC_PUBLIC, "octo$renderEntity", ENTITY_BRIDGE, null,
                        null);
                bridge.visitCode();
                bridge.visitVarInsn(Opcodes.ALOAD, 0);
                bridge.visitVarInsn(Opcodes.ALOAD, 1);
                bridge.visitTypeInsn(Opcodes.CHECKCAST, ENTITY);
                bridge.visitVarInsn(Opcodes.FLOAD, 2);
                bridge.visitVarInsn(Opcodes.FLOAD, 3);
                bridge.visitVarInsn(Opcodes.ALOAD, 4);
                bridge.visitTypeInsn(Opcodes.CHECKCAST, POSE_STACK);
                bridge.visitVarInsn(Opcodes.ALOAD, 5);
                bridge.visitTypeInsn(Opcodes.CHECKCAST, BUFFERS);
                bridge.visitVarInsn(Opcodes.ILOAD, 6);
                bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, drawName, ENTITY_OLD, false);
                bridge.visitInsn(Opcodes.RETURN);
                bridge.visitMaxs(0, 0);
                bridge.visitEnd();
                super.visitEnd();
            }
        }, 0);

        return writer.toByteArray();
    }

    private static void writeCreateState(ClassVisitor writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "createRenderState", CREATE_STATE, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "newState", "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        method.visitTypeInsn(Opcodes.CHECKCAST, STATE);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeExtract(ClassVisitor writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "extractRenderState", EXTRACT, null, null);
        method.visitCode();
        // The game's own part first: position, light, breaking progress.
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitVarInsn(Opcodes.ALOAD, 5);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, STATE, "extractBase",
                "(L" + BLOCK_ENTITY + ";L" + STATE + ";L" + CRUMBLING + ";)V", false);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.FLOAD, 3);
        method.visitVarInsn(Opcodes.ALOAD, 4);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "capture",
                "(Ljava/lang/Object;Ljava/lang/Object;FLjava/lang/Object;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeSubmit(ClassVisitor writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "submit", SUBMIT, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "submit",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void writeBridge(ClassVisitor writer, String owner, String drawName, String drawDesc,
            boolean camera) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "octo$render", BRIDGE, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitTypeInsn(Opcodes.CHECKCAST, BLOCK_ENTITY);
        method.visitVarInsn(Opcodes.FLOAD, 2);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitTypeInsn(Opcodes.CHECKCAST, POSE_STACK);
        method.visitVarInsn(Opcodes.ALOAD, 4);
        method.visitTypeInsn(Opcodes.CHECKCAST, BUFFERS);
        method.visitVarInsn(Opcodes.ILOAD, 5);
        method.visitVarInsn(Opcodes.ILOAD, 6);

        if (camera) {
            method.visitVarInsn(Opcodes.ALOAD, 7);
            method.visitTypeInsn(Opcodes.CHECKCAST, VEC3);
        }

        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, drawName, drawDesc, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    /**
     * Whether a class, as read before any transformer, is one this bridges:
     * for {@link AbstractGaps}, which reads the hierarchy that way and would
     * otherwise answer a bridged renderer's subclasses' render-state methods
     * with defaults that hide the bridge.
     */
    static boolean bridges(java.util.Collection<String> interfaces, java.util.Collection<String> methods) {
        if ((methods.contains("render" + ENTITY_OLD) || methods.contains("submit" + ENTITY_OLD))
                && !methods.contains("createRenderState" + ENTITY_CREATE)) {
            return true;
        }

        return interfaces.contains(RENDERER) && !methods.contains("submit" + SUBMIT)
                && (methods.contains("render" + OLD) || methods.contains("submit" + OLD)
                        || methods.contains("render" + OLD_WITH_CAMERA) || methods.contains("submit" + OLD_WITH_CAMERA));
    }

    /** Whether a method, by name and descriptor, is one the bridge writes. */
    static boolean writes(String method) {
        return method.equals("createRenderState" + CREATE_STATE) || method.equals("extractRenderState" + EXTRACT)
                || method.equals("submit" + SUBMIT) || method.equals("createRenderState" + ENTITY_CREATE);
    }
}
