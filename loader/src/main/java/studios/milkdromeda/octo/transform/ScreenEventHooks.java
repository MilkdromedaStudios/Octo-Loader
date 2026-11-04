package studios.milkdromeda.octo.transform;

import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Puts a call to {@link studios.milkdromeda.octo.bridge.forge.NeoScreenEvents}
 * where NeoForge's patched client fires its screen events:
 *
 * <ul>
 * <li>{@code Screen.init(int, int)} and {@code rebuildWidgets()}: their call to
 * {@code this.init()}, for {@code ScreenEvent.Init};</li>
 * <li>the start of {@code Gui.setScreen}, for {@code Opening} and
 * {@code Closing}, and its drawing of the screen, for {@code Render};</li>
 * <li>{@code MouseHandler} and {@code KeyboardHandler}: their calls handing a
 * click, drag, scroll, key or character to the screen, for the input
 * events.</li>
 * </ul>
 *
 * <p>Each call site becomes a static call with the same arguments, so the
 * stack and the method's frames are untouched.
 */
public final class ScreenEventHooks implements Transformer {
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String MOUSE = "net/minecraft/client/MouseHandler";
    private static final String KEYBOARD = "net/minecraft/client/KeyboardHandler";
    private static final String BRIDGE = "studios/milkdromeda/octo/bridge/forge/NeoScreenEvents";

    /** The screen's own methods whose calls are redirected: name and descriptor, to the bridge method. */
    private static final Map<String, String> INPUT = Map.of(
            "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", "mouseClicked",
            "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z", "mouseReleased",
            "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z", "mouseDragged",
            "mouseScrolled(DDDD)Z", "mouseScrolled",
            "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z", "keyPressed",
            "keyReleased(Lnet/minecraft/client/input/KeyEvent;)Z", "keyReleased",
            "charTyped(Lnet/minecraft/client/input/CharacterEvent;)Z", "charTyped");

    @Override
    public String name() {
        return "screen-event-hooks";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(SCREEN) || className.equals(GUI) || className.equals(MOUSE)
                || className.equals(KEYBOARD);
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

                if (delegate == null) {
                    return null;
                }

                boolean initSite = className.equals(SCREEN)
                        && ((name.equals("init") && descriptor.equals("(II)V"))
                                || (name.equals("rebuildWidgets") && descriptor.equals("()V")));
                boolean setScreen = className.equals(GUI) && name.equals("setScreen")
                        && descriptor.equals("(L" + SCREEN + ";)V");

                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();

                        if (setScreen) {
                            // screen = NeoScreenEvents.opening(this, screen)
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "opening",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, SCREEN);
                            super.visitVarInsn(Opcodes.ASTORE, 1);
                            changed[0] = true;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(SCREEN)) {
                            if (initSite && method.equals("init") && desc.equals("()V")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "init", "(Ljava/lang/Object;)V",
                                        false);
                                changed[0] = true;
                                return;
                            }

                            if (className.equals(GUI) && method.equals("extractRenderStateWithTooltipAndSubtitles")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "extract", bridged(desc), false);
                                changed[0] = true;
                                return;
                            }

                            String bridged = (className.equals(MOUSE) || className.equals(KEYBOARD))
                                    ? INPUT.get(method + desc) : null;

                            if (bridged != null) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, bridged, bridged(desc), false);
                                changed[0] = true;
                                return;
                            }
                        }

                        super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }

    /**
     * The bridge's descriptor for a call on a screen: the screen first, then the
     * call's arguments, every object as {@code Object} (the bridge reaches the
     * game's classes by reflection), primitives and the return as they are.
     */
    static String bridged(String descriptor) {
        org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        org.objectweb.asm.Type object = org.objectweb.asm.Type.getType(Object.class);
        org.objectweb.asm.Type[] out = new org.objectweb.asm.Type[arguments.length + 1];
        out[0] = object;

        for (int i = 0; i < arguments.length; i++) {
            int sort = arguments[i].getSort();
            out[i + 1] = sort == org.objectweb.asm.Type.OBJECT || sort == org.objectweb.asm.Type.ARRAY ? object
                    : arguments[i];
        }

        return org.objectweb.asm.Type.getMethodDescriptor(org.objectweb.asm.Type.getReturnType(descriptor), out);
    }
}
