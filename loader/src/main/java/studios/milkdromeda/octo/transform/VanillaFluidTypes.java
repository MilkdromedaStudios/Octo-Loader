package studios.milkdromeda.octo.transform;

import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The fluid types NeoForge's patch gives the game's own fluids, added back.
 *
 * <p>NeoForge makes every fluid answer {@code getFluidType()}, an abstract
 * method of its {@code IFluidExtension}, and its patch has water, lava and the
 * empty fluid answer the types NeoForge registers for them. Octo adds the
 * interface as NeoForge's jar asks, and registers those types, but without
 * the method each of the game's fluids answered with an
 * {@code AbstractMethodError}: Sodium, asking for the fluid's client
 * extensions, ended the game at the first water it built.
 *
 * <p>So each of the three answers {@code NeoForgeMod.<TYPE>.value()}, as it
 * does under NeoForge. A mod's own fluid declares its own.
 */
public final class VanillaFluidTypes implements Transformer {
    static final Map<String, String> TYPES = Map.of(
            "net/minecraft/world/level/material/WaterFluid", "WATER_TYPE",
            "net/minecraft/world/level/material/LavaFluid", "LAVA_TYPE",
            "net/minecraft/world/level/material/EmptyFluid", "EMPTY_TYPE");
    private static final String METHOD = "getFluidType";
    private static final String FLUID_TYPE = "net/neoforged/neoforge/fluids/FluidType";
    static final String DESCRIPTOR = "()L" + FLUID_TYPE + ";";
    private static final String MOD = "net/neoforged/neoforge/common/NeoForgeMod";
    private static final String HOLDER = "net/minecraft/core/Holder";

    @Override
    public String name() {
        return "vanilla-fluid-types";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return TYPES.containsKey(className);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        boolean[] declared = new boolean[1];

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                declared[0] |= name.equals(METHOD) && descriptor.equals(DESCRIPTOR);
                return null;
            }
        }, ClassReader.SKIP_CODE);

        if (declared[0]) {
            return bytes;
        }

        String type = TYPES.get(className);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                MethodVisitor method = cv.visitMethod(Opcodes.ACC_PUBLIC, METHOD, DESCRIPTOR, null, null);
                method.visitCode();
                method.visitFieldInsn(Opcodes.GETSTATIC, MOD, type, "L" + HOLDER + ";");
                method.visitMethodInsn(Opcodes.INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true);
                method.visitTypeInsn(Opcodes.CHECKCAST, FLUID_TYPE);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                super.visitEnd();
            }
        }, 0);

        return writer.toByteArray();
    }
}
