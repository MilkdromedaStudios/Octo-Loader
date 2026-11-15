package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Two things the game refuses that mods from older versions do in good faith.
 *
 * <p>A block that adds a property its superclass has since started adding
 * itself: 26.x's multiface block is waterlogged on its own, and Wither
 * Storm's tainted veins, which made it so themselves, were refused their whole
 * state definition for naming the same property twice. Adding the very same
 * property again is now adding it once.
 *
 * <p>And an entry put in one of the game's registries from a mod's set-up,
 * after the registries were frozen: a structure piece type, which Forge left
 * open until then. While a mod is taking its turn in start-up, a registry
 * whose entries do not make their own holders takes the entry; refused, the
 * rest of that mod's set-up never ran.
 */
public final class LegacyRegistryHooks implements Transformer {
    private static final String BUILDER = "net/minecraft/world/level/block/state/StateDefinition$Builder";
    private static final String PROPERTY = "net/minecraft/world/level/block/state/properties/Property";
    private static final String REGISTRY = "net/minecraft/core/MappedRegistry";
    private static final String HELPER = "studios/milkdromeda/octo/bridge/forge/Registries";

    @Override
    public String name() {
        return "legacy-registry-hooks";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(BUILDER) || className.equals(REGISTRY);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean property = className.equals(BUILDER) && name.equals("validateProperty")
                        && descriptor.equals("(L" + PROPERTY + ";)V");
                boolean write = className.equals(REGISTRY) && name.equals("validateWrite") && descriptor.endsWith(")V");

                if (!property && !write) {
                    return delegate;
                }

                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label carryOn = new Label();

                        if (property) {
                            // if (this.properties.get(property.getName()) == property) return;
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, BUILDER, "properties", "Ljava/util/Map;");
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROPERTY, "getName", "()Ljava/lang/String;",
                                    false);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitJumpInsn(Opcodes.IF_ACMPNE, carryOn);
                        } else {
                            // if (Registries.allowsLateWrite(this)) return;
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "allowsLateWrite",
                                    "(Ljava/lang/Object;)Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, carryOn);
                        }

                        super.visitInsn(Opcodes.RETURN);
                        super.visitLabel(carryOn);
                        super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        changed[0] = true;
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
