package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * {@code EntityRenderers.register} keeping each provider behind
 * {@link studios.milkdromeda.octo.bridge.client.RendererProviders}, so that
 * one renderer failing to be made does not cost the game all of them.
 */
public final class RendererProviderGuard implements Transformer {
    private static final String RENDERERS = "net/minecraft/client/renderer/entity/EntityRenderers";
    private static final String PROVIDER = "net/minecraft/client/renderer/entity/EntityRendererProvider";
    private static final String REGISTER = "(Lnet/minecraft/world/entity/EntityType;L" + PROVIDER + ";)V";

    @Override
    public String name() {
        return "renderer-provider-guard";
    }

    private static final String DISPATCHER = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    private static final String RENDERER = "net/minecraft/client/renderer/entity/EntityRenderer";
    private static final String STATE = "net/minecraft/client/renderer/entity/state/EntityRenderState";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String HELPER = "studios/milkdromeda/octo/bridge/client/RendererProviders";

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(RENDERERS) || className.equals(DISPATCHER) || className.equals(RENDERER);
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

                // The renderer looked up for an entity or its state: a stand-in
                // where the entity's own cannot draw here.
                boolean byEntity = className.equals(DISPATCHER) && name.equals("getRenderer")
                        && descriptor.equals("(L" + ENTITY + ";)L" + RENDERER + ";");
                boolean byState = className.equals(DISPATCHER) && name.equals("getRenderer")
                        && descriptor.equals("(L" + STATE + ";)L" + RENDERER + ";");

                if (byEntity || byState) {
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);

                                if (byEntity) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "forEntity",
                                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                            false);
                                } else {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "forState",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                                }

                                super.visitTypeInsn(Opcodes.CHECKCAST, RENDERER);
                                changed[0] = true;
                            }

                            super.visitInsn(opcode);
                        }
                    };
                }

                // And its name, shown whenever a stand-in draws it.
                if (className.equals(RENDERER) && name.equals("extractRenderState")
                        && descriptor.equals("(L" + ENTITY + ";L" + STATE + ";F)V")) {
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitVarInsn(Opcodes.ALOAD, 2);
                                super.visitVarInsn(Opcodes.FLOAD, 3);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "nameTag",
                                        "(Ljava/lang/Object;Ljava/lang/Object;F)V", false);
                                changed[0] = true;
                            }

                            super.visitInsn(opcode);
                        }
                    };
                }

                if (name.equals("createEntityRenderers") && descriptor.endsWith(")Ljava/util/Map;")
                        && (access & Opcodes.ACC_STATIC) != 0) {
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "studios/milkdromeda/octo/bridge/client/RendererProviders", "withFallbacks",
                                        "(Ljava/util/Map;Ljava/lang/Object;)Ljava/util/Map;", false);
                                changed[0] = true;
                            }

                            super.visitInsn(opcode);
                        }
                    };
                }

                if (!name.equals("register") || !descriptor.equals(REGISTER) || (access & Opcodes.ACC_STATIC) == 0) {
                    return delegate;
                }

                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "studios/milkdromeda/octo/bridge/client/RendererProviders", "guard",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                        super.visitTypeInsn(Opcodes.CHECKCAST, PROVIDER);
                        super.visitVarInsn(Opcodes.ASTORE, 1);
                        changed[0] = true;
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
