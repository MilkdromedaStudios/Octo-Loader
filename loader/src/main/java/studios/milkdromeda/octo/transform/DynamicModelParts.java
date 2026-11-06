package studios.milkdromeda.octo.transform;

import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The part collection by position that NeoForge's patch gives the game's
 * weighted and multipart block models, added back.
 *
 * <p>NeoForge makes both of them {@code DynamicBlockStateModel}s, an
 * interface whose {@code collectParts(level, pos, state, random, parts)} is
 * abstract, and its patch writes that method into each. Octo adds the
 * interface as NeoForge's jar asks, so without the method each model answered
 * the call with an {@code AbstractMethodError}, and Sodium, which collects
 * every block's parts so, ended the game building the first chunk.
 *
 * <p>The game's own models choose their parts from the random source alone,
 * so the method added back does exactly that: {@code collectParts(random, parts)}.
 */
public final class DynamicModelParts implements Transformer {
    static final Set<String> MODELS = Set.of(
            "net/minecraft/client/renderer/block/dispatch/WeightedVariants",
            "net/minecraft/client/renderer/block/dispatch/multipart/MultiPartModel");
    private static final String METHOD = "collectParts";
    static final String BY_POSITION = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/util/RandomSource;Ljava/util/List;)V";
    static final String BY_RANDOM = "(Lnet/minecraft/util/RandomSource;Ljava/util/List;)V";

    @Override
    public String name() {
        return "dynamic-model-parts";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return MODELS.contains(className);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        boolean[] declared = new boolean[2];

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (name.equals(METHOD)) {
                    declared[0] |= descriptor.equals(BY_POSITION);
                    declared[1] |= descriptor.equals(BY_RANDOM) && (access & Opcodes.ACC_ABSTRACT) == 0;
                }

                return null;
            }
        }, ClassReader.SKIP_CODE);

        // Already NeoForge's own, or nothing of the game's to hand the call to.
        if (declared[0] || !declared[1]) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                MethodVisitor method = cv.visitMethod(Opcodes.ACC_PUBLIC, METHOD, BY_POSITION, null, null);
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitVarInsn(Opcodes.ALOAD, 5);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, METHOD, BY_RANDOM, false);
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                super.visitEnd();
            }
        }, 0);

        return writer.toByteArray();
    }
}
