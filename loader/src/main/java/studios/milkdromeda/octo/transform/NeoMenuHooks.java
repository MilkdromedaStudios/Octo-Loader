package studios.milkdromeda.octo.transform;

import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The menu methods NeoForge's player and menu type interfaces declare and its
 * patched game implements, implemented here by
 * {@link studios.milkdromeda.octo.bridge.forge.NeoMenus}:
 *
 * <ul>
 * <li>{@code Player.openMenu(MenuProvider, Consumer<RegistryFriendlyByteBuf>)}
 * and {@code Player.openMenu(MenuProvider, BlockPos)}, whose interface default
 * opens nothing;</li>
 * <li>{@code MenuType.create(int, Inventory, RegistryFriendlyByteBuf)}, which
 * the interface leaves abstract, so every call was an
 * {@code AbstractMethodError}.</li>
 * </ul>
 */
public final class NeoMenuHooks implements Transformer {
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String MENU_TYPE = "net/minecraft/world/inventory/MenuType";
    private static final String BRIDGE = "studios/milkdromeda/octo/bridge/forge/NeoMenus";
    private static final String OPEN_WITH_DATA = "(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)"
            + "Ljava/util/OptionalInt;";
    private static final String OPEN_AT = "(Lnet/minecraft/world/MenuProvider;Lnet/minecraft/core/BlockPos;)"
            + "Ljava/util/OptionalInt;";
    private static final String CREATE = "(ILnet/minecraft/world/entity/player/Inventory;"
            + "Lnet/minecraft/network/RegistryFriendlyByteBuf;)Lnet/minecraft/world/inventory/AbstractContainerMenu;";

    @Override
    public String name() {
        return "neo-menu-hooks";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(PLAYER) || className.equals(MENU_TYPE);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        Set<String> declared = new HashSet<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                declared.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE);

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] added = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                if (className.equals(PLAYER)) {
                    if (!declared.contains("openMenu" + OPEN_WITH_DATA)) {
                        bridge(cv, "openMenu", OPEN_WITH_DATA, "open",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/OptionalInt;");
                        added[0] = true;
                    }

                    if (!declared.contains("openMenu" + OPEN_AT)) {
                        bridge(cv, "openMenu", OPEN_AT, "openAt",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/OptionalInt;");
                        added[0] = true;
                    }
                } else if (!declared.contains("create" + CREATE)) {
                    MethodVisitor method = cv.visitMethod(Opcodes.ACC_PUBLIC, "create", CREATE, null, null);
                    method.visitCode();
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ILOAD, 1);
                    method.visitVarInsn(Opcodes.ALOAD, 2);
                    method.visitVarInsn(Opcodes.ALOAD, 3);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "create",
                            "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                    method.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/world/inventory/AbstractContainerMenu");
                    method.visitInsn(Opcodes.ARETURN);
                    method.visitMaxs(0, 0);
                    method.visitEnd();
                    added[0] = true;
                }

                super.visitEnd();
            }
        }, 0);

        return added[0] ? writer.toByteArray() : bytes;
    }

    /** A method taking (this, a, b) straight to a static bridge method of three objects. */
    private static void bridge(ClassVisitor cv, String name, String descriptor, String target, String targetDescriptor) {
        MethodVisitor method = cv.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, target, targetDescriptor, false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
