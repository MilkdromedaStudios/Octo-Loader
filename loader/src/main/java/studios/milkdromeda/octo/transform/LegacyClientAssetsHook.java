package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Hands older mods' client assets to
 * {@link studios.milkdromeda.octo.bridge.LegacyClientAssets} on their way in:
 *
 * <ul>
 * <li>the item definition files {@code ClientItemInfoLoader} lists, as the
 * method that lists them returns ({@code (ResourceManager) -> Map}, a lambda
 * found by its shape rather than its name);</li>
 * <li>the translations a {@code ClientLanguage} is made with, as its
 * constructor starts;</li>
 * <li>every model file the game reads ({@code CuboidModel.fromStream}), to
 * {@link studios.milkdromeda.octo.bridge.client.CustomModelLoaders}, for
 * NeoForge's and Forge's OBJ and composite models. The game's own reading
 * stays, as {@code octo$fromStream}.</li>
 * </ul>
 */
public final class LegacyClientAssetsHook implements Transformer {
    private static final String ITEM_INFO_LOADER = "net/minecraft/client/resources/model/ClientItemInfoLoader";
    private static final String LANGUAGE = "net/minecraft/client/resources/language/ClientLanguage";
    private static final String LISTING = "(Lnet/minecraft/server/packs/resources/ResourceManager;)Ljava/util/Map;";
    private static final String BRIDGE = "studios/milkdromeda/octo/bridge/LegacyClientAssets";
    private static final String CUBOID_MODEL = "net/minecraft/client/resources/model/cuboid/CuboidModel";
    private static final String FROM_STREAM = "(Ljava/io/Reader;)L" + CUBOID_MODEL + ";";

    @Override
    public String name() {
        return "legacy-client-assets";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(ITEM_INFO_LOADER) || className.equals(LANGUAGE) || className.equals(CUBOID_MODEL);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = { false };
        boolean items = className.equals(ITEM_INFO_LOADER);
        boolean models = className.equals(CUBOID_MODEL);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (models) {
                    if (name.equals("fromStream") && descriptor.equals(FROM_STREAM)) {
                        changed[0] = true;
                        return super.visitMethod(access, "octo$fromStream", descriptor, signature, exceptions);
                    }

                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (delegate == null) {
                    return delegate;
                }

                if (items && (access & Opcodes.ACC_STATIC) != 0 && descriptor.equals(LISTING)) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                // The listing is on the stack: hand it over with the resource manager.
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "withItemDefinitions",
                                        "(Ljava/util/Map;Ljava/lang/Object;)Ljava/util/Map;", false);
                            }

                            super.visitInsn(opcode);
                        }
                    };
                }

                if (!items && name.equals("<init>") && descriptor.startsWith("(Ljava/util/Map;")) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "withBlockItemNames",
                                    "(Ljava/util/Map;)Ljava/util/Map;", false);
                            super.visitVarInsn(Opcodes.ASTORE, 1);
                        }
                    };
                }

                return delegate;
            }

            @Override
            public void visitEnd() {
                if (models && changed[0]) {
                    // fromStream(reader) = CustomModelLoaders.fromStream(reader, CuboidModel.class)
                    MethodVisitor method = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fromStream",
                            FROM_STREAM, null, null);
                    method.visitCode();
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitLdcInsn(org.objectweb.asm.Type.getObjectType(CUBOID_MODEL));
                    method.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "studios/milkdromeda/octo/bridge/client/CustomModelLoaders", "fromStream",
                            "(Ljava/io/Reader;Ljava/lang/Class;)Ljava/lang/Object;", false);
                    method.visitTypeInsn(Opcodes.CHECKCAST, CUBOID_MODEL);
                    method.visitInsn(Opcodes.ARETURN);
                    method.visitMaxs(0, 0);
                    method.visitEnd();
                }

                super.visitEnd();
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
