package studios.milkdromeda.octo.transform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Lets an older mod's screens and widgets draw on 26.x.
 *
 * <p>26.1 turned drawing into collecting what to draw: the game no longer
 * calls a screen's {@code render(GuiGraphics, ...)}, {@code renderBg},
 * {@code renderLabels} or a widget's {@code renderWidget}, but
 * {@code extractRenderState(GuiGraphicsExtractor, ...)} and its kin. A mod
 * built before then overrides the old methods, which nothing calls, and
 * calls up into the game's old ones, which are gone: every Create menu opened
 * as an empty frame, and every Create screen as a blank one.
 *
 * <p>Octo carries the old {@code GuiGraphics}, drawing through the extractor
 * (it is in {@code src/legacyclient}). This joins the two:
 *
 * <ul>
 * <li>The first class of a mod's screen or widget hierarchy — the one whose
 * superclass is the game's — is given the old methods it used to inherit,
 * each doing what it did through the game's new one ({@code render} drawing
 * the background first, as it did; a container screen's background calling
 * {@code renderBg}), and the new methods the game calls, each calling the old
 * one with a {@code GuiGraphics} over the extractor. The old ones, declared
 * or given, answer every call the mod makes up its own hierarchy as before.
 * A super call from that class into the game's old method goes to what it was
 * given.</li>
 * <li>A call from a mod to an old method of one of the game's own types (a
 * vanilla widget's {@code render}, {@code Renderable.render}) is made to the
 * new one.</li>
 * <li>A lambda a mod made into a {@code Renderable} is made with the new
 * method.</li>
 * </ul>
 */
public final class LegacyScreenBridge implements Transformer {
    private static final OctoLog LOG = OctoLog.of(LegacyScreenBridge.class);

    static final String GG = "net/minecraft/client/gui/GuiGraphics";
    static final String GGE = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String CONTAINER = "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen";
    private static final String WIDGET = "net/minecraft/client/gui/components/AbstractWidget";
    private static final String BUTTON = "net/minecraft/client/gui/components/AbstractButton";
    private static final String TEXT_COLLECTOR = "net/minecraft/client/gui/ActiveTextCollector";
    private static final String HOVER_EFFECTS = GGE + "$HoveredTextEffects";
    private static final String RENDERABLE = "net/minecraft/client/gui/components/Renderable";
    private static final String OLD = "L" + GG + ";";
    private static final String NEW = "L" + GGE + ";";
    private static final String LAMBDA = "java/lang/invoke/LambdaMetafactory";

    /** An old drawing method and the one the game has in its place. */
    record Mapping(String oldName, String oldDescriptor, String newName, String newDescriptor) {
        String newFrom(String oldDescriptor) {
            return newDescriptor;
        }
    }

    static final Mapping RENDER = new Mapping("render", "(" + OLD + "IIF)V", "extractRenderState", "(" + NEW + "IIF)V");
    static final Mapping BACKGROUND = new Mapping("renderBackground", "(" + OLD + "IIF)V", "extractBackground",
            "(" + NEW + "IIF)V");
    /** 1.20.1's, which took no mouse position and darkened the screen. */
    static final Mapping BACKGROUND_1_20 = new Mapping("renderBackground", "(" + OLD + ")V", "extractBackground",
            "(" + NEW + "IIF)V");
    static final Mapping LABELS = new Mapping("renderLabels", "(" + OLD + "II)V", "extractLabels", "(" + NEW + "II)V");
    static final Mapping TOOLTIP = new Mapping("renderTooltip", "(" + OLD + "II)V", "extractTooltip",
            "(" + NEW + "II)V");
    static final Mapping WIDGET_BODY = new Mapping("renderWidget", "(" + OLD + "IIF)V", "extractWidgetRenderState",
            "(" + NEW + "IIF)V");
    static final Mapping MENU_BACKGROUND = new Mapping("renderMenuBackground", "(" + OLD + ")V",
            "extractMenuBackground", "(" + NEW + ")V");
    static final Mapping TRANSPARENT = new Mapping("renderTransparentBackground", "(" + OLD + ")V",
            "extractTransparentBackground", "(" + NEW + ")V");
    /**
     * A button's label, which its old {@code renderWidget} drew after the
     * button itself; the game's default label is what a button draws now.
     */
    static final Mapping BUTTON_LABEL = new Mapping("renderString", "(" + OLD + "Lnet/minecraft/client/gui/Font;I)V",
            "extractDefaultLabel", "(L" + TEXT_COLLECTOR + ";)V");
    /** A container screen's own background, called by its old {@code renderBackground}; nothing replaces it. */
    private static final String RENDER_BG = "renderBg";
    private static final String RENDER_BG_DESCRIPTOR = "(" + OLD + "FII)V";

    static final List<Mapping> MAPPINGS = List.of(RENDER, BACKGROUND, BACKGROUND_1_20, LABELS, TOOLTIP, WIDGET_BODY,
            MENU_BACKGROUND, TRANSPARENT, BUTTON_LABEL);

    private static final ByteScan NAMES_GUI = new ByteScan(List.of(GG));

    private final MemberIndex index;

    public LegacyScreenBridge(MemberIndex index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "legacy-screen-bridge";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        String superName = reader.getSuperName();
        boolean firstOfMods = superName != null && isGame(superName);

        if (!(firstOfMods || NAMES_GUI.matches(bytes)) || !index.exists(GGE)) {
            return bytes;
        }

        boolean isInterface = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
        boolean isAbstract = (reader.getAccess() & Opcodes.ACC_ABSTRACT) != 0;
        Set<String> declared = new HashSet<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                declared.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // The first class of a screen or widget hierarchy that is the mod's.
        // Asked of its supertypes, never of the class itself: the index may be
        // reading this very class, to answer another transformer about it.
        Family family = isInterface || !firstOfMods ? Family.NONE
                : family(superName, reader.getInterfaces());
        // Each call rewritten keeps the stack as it was, so the frames the
        // class has stay right; only the methods added are new.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        Rewriter rewriter = new Rewriter(writer, className, superName, family, isInterface);
        reader.accept(rewriter, 0);

        if (!rewriter.changed && family == Family.NONE) {
            return bytes;
        }

        rewriter.finish(declared, isAbstract);
        LOG.debug("{} draws through this Minecraft's GuiGraphicsExtractor", className.replace('/', '.'));
        return writer.toByteArray();
    }

    enum Family {
        NONE, RENDERABLE, WIDGET, BUTTON, SCREEN, CONTAINER
    }

    private Family family(String superName, String[] interfaces) {
        if (index.isSubtype(superName, CONTAINER)) {
            return Family.CONTAINER;
        }

        if (index.isSubtype(superName, SCREEN)) {
            return Family.SCREEN;
        }

        if (index.isSubtype(superName, BUTTON)) {
            return Family.BUTTON;
        }

        if (index.isSubtype(superName, WIDGET)) {
            return Family.WIDGET;
        }

        if (index.isSubtype(superName, RENDERABLE)) {
            return Family.RENDERABLE;
        }

        for (String type : interfaces) {
            if (index.isSubtype(type, RENDERABLE)) {
                return Family.RENDERABLE;
            }
        }

        return Family.NONE;
    }

    /** The mappings whose old method a class of the family used to inherit from the game. */
    static List<Mapping> inherited(Family family) {
        return switch (family) {
            case CONTAINER -> List.of(RENDER, BACKGROUND, BACKGROUND_1_20, LABELS, TOOLTIP, MENU_BACKGROUND,
                    TRANSPARENT);
            case SCREEN -> List.of(RENDER, BACKGROUND, BACKGROUND_1_20, MENU_BACKGROUND, TRANSPARENT);
            case WIDGET -> List.of(RENDER, WIDGET_BODY);
            case BUTTON -> List.of(RENDER, WIDGET_BODY, BUTTON_LABEL);
            default -> List.of();
        };
    }

    private final class Rewriter extends ClassVisitor {
        private final String className;
        private final String superName;
        private final Family family;
        private final boolean isInterface;
        private final List<Runnable> helpers = new ArrayList<>();
        private final Set<String> helperNames = new HashSet<>();
        private final Set<Mapping> superCalls = new HashSet<>();
        private int lambdas;
        boolean changed;

        Rewriter(ClassVisitor next, String className, String superName, Family family, boolean isInterface) {
            super(Opcodes.ASM9, next);
            this.className = className;
            this.superName = superName;
            this.family = family;
            this.isInterface = isInterface;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, next) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String method, String methodDescriptor,
                        boolean onInterface) {
                    Mapping mapping = gameCall(owner, method, methodDescriptor);

                    if (mapping == null || (opcode == Opcodes.INVOKESPECIAL && method.equals("<init>"))
                            || (mapping == BUTTON_LABEL && opcode != Opcodes.INVOKESPECIAL)) {
                        super.visitMethodInsn(opcode, owner, method, methodDescriptor, onInterface);
                        return;
                    }

                    changed = true;

                    if (opcode == Opcodes.INVOKESPECIAL) {
                        // super.render(graphics, ...) from the class the old methods are given to.
                        superCalls.add(mapping);
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, className, superHelper(mapping),
                                mapping.oldDescriptor(), isInterface);
                        return;
                    }

                    String helper = callHelper(opcode, owner, onInterface, mapping);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, className, helper,
                            "(L" + owner + ";" + mapping.oldDescriptor().substring(1), isInterface);
                }

                @Override
                public void visitInvokeDynamicInsn(String lambdaName, String lambdaDescriptor, Handle bootstrap,
                        Object... arguments) {
                    if (bootstrap.getOwner().equals(LAMBDA) && lambdaName.equals("render")
                            && Type.getReturnType(lambdaDescriptor).getInternalName().equals(RENDERABLE)
                            && arguments.length >= 3 && arguments[1] instanceof Handle implementation
                            && RENDER.oldDescriptor().equals(arguments[0].toString())) {
                        changed = true;
                        String adapter = lambdaAdapter(lambdaDescriptor, implementation);
                        Type newType = Type.getMethodType(RENDER.newDescriptor());
                        Object[] rewritten = arguments.clone();
                        rewritten[0] = newType;
                        rewritten[1] = new Handle(Opcodes.H_INVOKESTATIC, className, adapter,
                                adapterDescriptor(lambdaDescriptor), isInterface);
                        rewritten[2] = newType;
                        super.visitInvokeDynamicInsn(RENDER.newName(), lambdaDescriptor, bootstrap, rewritten);
                        return;
                    }

                    super.visitInvokeDynamicInsn(lambdaName, lambdaDescriptor, bootstrap, arguments);
                }
            };
        }

        /** The mapping of a call to an old drawing method of one of the game's own types, which it no longer has. */
        private Mapping gameCall(String owner, String method, String descriptor) {
            if (!isGame(owner) || owner.startsWith(GG) || !descriptor.contains(OLD)) {
                return null;
            }

            for (Mapping mapping : MAPPINGS) {
                if (mapping.oldName().equals(method) && mapping.oldDescriptor().equals(descriptor)
                        && !index.hasMethod(owner, method, descriptor)
                        && index.hasMethod(owner, mapping.newName(), mapping.newDescriptor())) {
                    return mapping;
                }
            }

            return null;
        }

        private String superHelper(Mapping mapping) {
            return "octo$super$" + mapping.oldName() + (mapping == BACKGROUND_1_20 ? "$old" : "");
        }

        /** A static method calling the new method of a game type for the old one, through the given graphics. */
        private String callHelper(int opcode, String owner, boolean onInterface, Mapping mapping) {
            String name = "octo$gui$" + mapping.oldName() + "$" + owner.replace('/', '_')
                    + (mapping == BACKGROUND_1_20 ? "$old" : "");

            if (helperNames.add(name)) {
                helpers.add(() -> {
                    String descriptor = "(L" + owner + ";" + mapping.oldDescriptor().substring(1);
                    MethodVisitor method = cv.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                            | Opcodes.ACC_SYNTHETIC, name, descriptor, null, null);
                    method.visitCode();
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    enter(method, 1);
                    passNewArguments(method, mapping, 1);
                    method.visitMethodInsn(onInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner,
                            mapping.newName(), mapping.newDescriptor(), onInterface);
                    leave(method, 1);
                    method.visitInsn(Opcodes.RETURN);
                    method.visitMaxs(0, 0);
                    method.visitEnd();
                });
            }

            return name;
        }

        private String adapterDescriptor(String lambdaDescriptor) {
            Type[] captured = Type.getArgumentTypes(lambdaDescriptor);
            Type[] sam = Type.getArgumentTypes(RENDER.newDescriptor());
            Type[] all = new Type[captured.length + sam.length];
            System.arraycopy(captured, 0, all, 0, captured.length);
            System.arraycopy(sam, 0, all, captured.length, sam.length);
            return Type.getMethodDescriptor(Type.VOID_TYPE, all);
        }

        /** A static method a lambda made into a {@code Renderable} is made from now, calling its body. */
        private String lambdaAdapter(String lambdaDescriptor, Handle implementation) {
            String name = "octo$renderable$" + lambdas++;
            String descriptor = adapterDescriptor(lambdaDescriptor);
            helpers.add(() -> {
                MethodVisitor method = cv.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        name, descriptor, null, null);
                method.visitCode();
                Type[] parameters = Type.getArgumentTypes(descriptor);
                int slot = 0;

                for (int i = 0; i < parameters.length; i++) {
                    method.visitVarInsn(parameters[i].getOpcode(Opcodes.ILOAD), slot);

                    if (i == parameters.length - 4) {
                        // The extractor the game passes, as the graphics the body takes.
                        method.visitMethodInsn(Opcodes.INVOKESTATIC, GG, "octo$of", "(" + NEW + ")" + OLD, false);
                    }

                    slot += parameters[i].getSize();
                }

                int opcode = switch (implementation.getTag()) {
                    case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
                    case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                    default -> Opcodes.INVOKEVIRTUAL;
                };
                method.visitMethodInsn(opcode, implementation.getOwner(), implementation.getName(),
                        implementation.getDesc(), implementation.isInterface());

                if (Type.getReturnType(implementation.getDesc()).getSize() == 1) {
                    method.visitInsn(Opcodes.POP);
                } else if (Type.getReturnType(implementation.getDesc()).getSize() == 2) {
                    method.visitInsn(Opcodes.POP2);
                }

                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
            });
            return name;
        }

        /** Adds what the class needs once its own methods are all written. */
        void finish(Set<String> declared, boolean isAbstract) {
            Set<Mapping> given = new HashSet<>();

            if (family != Family.NONE) {
                for (Mapping mapping : inherited(family)) {
                    if (index.hasMethod(superName, mapping.newName(), mapping.newDescriptor())) {
                        given.add(mapping);
                    }
                }
            }

            // The old methods' bodies, as the game's did them, for the class's
            // own super calls and for the old methods it does not declare.
            Set<Mapping> bodies = new HashSet<>(superCalls);

            for (Mapping mapping : given) {
                if (!declared.contains(mapping.oldName() + mapping.oldDescriptor())) {
                    bodies.add(mapping);
                }
            }

            for (Mapping mapping : bodies) {
                writeSuperBody(mapping);
            }

            for (Mapping mapping : given) {
                if (!declared.contains(mapping.oldName() + mapping.oldDescriptor())) {
                    writeDelegate(mapping.oldName(), mapping.oldDescriptor(), superHelper(mapping));
                }
            }

            if (family == Family.CONTAINER && !declared.contains(RENDER_BG + RENDER_BG_DESCRIPTOR)) {
                writeAbstractOrNothing(RENDER_BG, RENDER_BG_DESCRIPTOR, isAbstract);
            }

            if ((family == Family.WIDGET || family == Family.BUTTON)
                    && !declared.contains(WIDGET_BODY.oldName() + WIDGET_BODY.oldDescriptor())
                    && !given.contains(WIDGET_BODY)) {
                writeAbstractOrNothing(WIDGET_BODY.oldName(), WIDGET_BODY.oldDescriptor(), isAbstract);
            }

            if (family == Family.RENDERABLE && !declared.contains(RENDER.oldName() + RENDER.oldDescriptor())
                    && isAbstract) {
                writeAbstractOrNothing(RENDER.oldName(), RENDER.oldDescriptor(), true);
            }

            // The new methods the game calls, each calling the old one.
            if (family != Family.NONE) {
                writeBridges(declared, isAbstract);
            }

            for (Runnable helper : helpers) {
                helper.run();
            }

            super.visitEnd();
        }

        private void writeBridges(Set<String> declared, boolean isAbstract) {
            switch (family) {
                case SCREEN, CONTAINER -> {
                    bridge(declared, RENDER.newName(), RENDER.newDescriptor(), RENDER);
                    // The old render draws the background itself, as it always did.
                    nothing(declared, BACKGROUND.newName(), BACKGROUND.newDescriptor());
                    bridge(declared, MENU_BACKGROUND.newName(), MENU_BACKGROUND.newDescriptor(), MENU_BACKGROUND);
                    bridge(declared, TRANSPARENT.newName(), TRANSPARENT.newDescriptor(), TRANSPARENT);

                    if (family == Family.CONTAINER) {
                        bridge(declared, LABELS.newName(), LABELS.newDescriptor(), LABELS);
                        bridge(declared, TOOLTIP.newName(), TOOLTIP.newDescriptor(), TOOLTIP);
                    }
                }
                case WIDGET -> bridge(declared, WIDGET_BODY.newName(), WIDGET_BODY.newDescriptor(), RENDER);
                // A button's own drawing is final now and calls its contents.
                case BUTTON -> bridge(declared, "extractContents", "(" + NEW + "IIF)V", RENDER);
                case RENDERABLE -> {
                    if (isAbstract || declared.contains(RENDER.oldName() + RENDER.oldDescriptor())) {
                        bridge(declared, RENDER.newName(), RENDER.newDescriptor(), RENDER);
                    }
                }
                default -> {
                }
            }
        }

        /** {@code newName(extractor, ...)} calling {@code this.old(GuiGraphics.of(extractor), ...)}. */
        private void bridge(Set<String> declared, String newName, String newDescriptor, Mapping old) {
            Integer access = gameAccess(newName, newDescriptor);

            if (access == null || declared.contains(newName + newDescriptor)) {
                return;
            }

            MethodVisitor method = cv.visitMethod(access, newName, newDescriptor, null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, GG, "octo$of", "(" + NEW + ")" + OLD, false);
            Type[] parameters = Type.getArgumentTypes(newDescriptor);

            for (int i = 1, slot = 2; i < parameters.length; i++) {
                method.visitVarInsn(parameters[i].getOpcode(Opcodes.ILOAD), slot);
                slot += parameters[i].getSize();
            }

            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, old.oldName(), old.oldDescriptor(), false);
            // What it raised over the rest (items over its tooltips), drawn last.
            method.visitMethodInsn(Opcodes.INVOKESTATIC, GG, "octo$flushRaised", "()V", false);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        private void nothing(Set<String> declared, String newName, String newDescriptor) {
            Integer access = gameAccess(newName, newDescriptor);

            if (access == null || declared.contains(newName + newDescriptor)) {
                return;
            }

            MethodVisitor method = cv.visitMethod(access, newName, newDescriptor, null, null);
            method.visitCode();
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        /** The access a method overriding the game's may have, or null when it cannot be overridden. */
        private Integer gameAccess(String name, String descriptor) {
            Integer access = index.methodAccess(superName, name, descriptor);

            if (access == null && family == Family.RENDERABLE) {
                access = index.methodAccess(RENDERABLE, name, descriptor);
            }

            if (access == null || (access & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) {
                return null;
            }

            return access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | 0;
        }

        /** The old method's body, as a private method: what the game's old one did, through its new one. */
        private void writeSuperBody(Mapping mapping) {
            MethodVisitor method = cv.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, superHelper(mapping),
                    mapping.oldDescriptor(), null, null);
            method.visitCode();

            if (mapping == WIDGET_BODY && family == Family.BUTTON) {
                // A button drew its sprite, then its label.
                method.visitVarInsn(Opcodes.ALOAD, 0);
                enter(method, 1);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "extractDefaultSprite", "(" + NEW + ")V",
                        false);
                leave(method, 1);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "getInstance",
                        "()Lnet/minecraft/client/Minecraft;", false);
                method.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "font",
                        "Lnet/minecraft/client/gui/Font;");
                method.visitInsn(Opcodes.ICONST_M1);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, BUTTON_LABEL.oldName(),
                        BUTTON_LABEL.oldDescriptor(), false);
            } else if (mapping == BUTTON_LABEL) {
                // The game's default label, as its plain button draws it.
                method.visitVarInsn(Opcodes.ALOAD, 0);
                enter(method, 1);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETSTATIC, HOVER_EFFECTS, "NONE", "L" + HOVER_EFFECTS + ";");
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GGE, "textRendererForWidget",
                        "(L" + WIDGET + ";L" + HOVER_EFFECTS + ";)L" + TEXT_COLLECTOR + ";", false);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, BUTTON_LABEL.newName(),
                        BUTTON_LABEL.newDescriptor(), false);
                leave(method, 1);
            } else if (mapping == RENDER && (family == Family.WIDGET || family == Family.BUTTON)) {
                // A widget's render drew its body.
                method.visitVarInsn(Opcodes.ALOAD, 0);
                loadOldArguments(method, mapping);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, WIDGET_BODY.oldName(),
                        WIDGET_BODY.oldDescriptor(), false);
            } else if (mapping == RENDER) {
                // A screen's render drew its background, then its widgets.
                method.visitVarInsn(Opcodes.ALOAD, 0);
                loadOldArguments(method, mapping);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, BACKGROUND.oldName(),
                        BACKGROUND.oldDescriptor(), false);
                superNew(method, mapping);
            } else if (mapping == BACKGROUND || mapping == BACKGROUND_1_20) {
                // Blurred or darkened once a frame, then a container's own background.
                Label drawn = new Label();
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GG, "octo$firstBackground", "()Z", false);
                method.visitJumpInsn(Opcodes.IFEQ, drawn);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                enter(method, 1);

                if (mapping == BACKGROUND) {
                    method.visitVarInsn(Opcodes.ILOAD, 2);
                    method.visitVarInsn(Opcodes.ILOAD, 3);
                    method.visitVarInsn(Opcodes.FLOAD, 4);
                } else {
                    method.visitInsn(Opcodes.ICONST_0);
                    method.visitInsn(Opcodes.ICONST_0);
                    method.visitInsn(Opcodes.FCONST_0);
                }

                method.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, BACKGROUND.newName(),
                        BACKGROUND.newDescriptor(), false);
                leave(method, 1);
                method.visitLabel(drawn);
                method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

                if (mapping == BACKGROUND && family == Family.CONTAINER) {
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitVarInsn(Opcodes.FLOAD, 4);
                    method.visitVarInsn(Opcodes.ILOAD, 2);
                    method.visitVarInsn(Opcodes.ILOAD, 3);
                    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, RENDER_BG, RENDER_BG_DESCRIPTOR, false);
                }
            } else if (index.hasMethod(superName, mapping.newName(), mapping.newDescriptor())
                    && !isAbstractInGame(mapping)) {
                superNew(method, mapping);
            }

            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        private boolean isAbstractInGame(Mapping mapping) {
            Integer access = index.methodAccess(superName, mapping.newName(), mapping.newDescriptor());
            return access == null || (access & Opcodes.ACC_ABSTRACT) != 0;
        }

        /** {@code super.newName(graphics.enter(), ...); graphics.leave()}. */
        private void superNew(MethodVisitor method, Mapping mapping) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            enter(method, 1);
            passNewArguments(method, mapping, 1);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, mapping.newName(), mapping.newDescriptor(),
                    false);
            leave(method, 1);
        }

        private void writeDelegate(String name, String descriptor, String target) {
            MethodVisitor method = cv.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            Type[] parameters = Type.getArgumentTypes(descriptor);

            for (int i = 0, slot = 1; i < parameters.length; i++) {
                method.visitVarInsn(parameters[i].getOpcode(Opcodes.ILOAD), slot);
                slot += parameters[i].getSize();
            }

            method.visitMethodInsn(Opcodes.INVOKESPECIAL, className, target, descriptor, false);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        private void writeAbstractOrNothing(String name, String descriptor, boolean isAbstract) {
            if (isAbstract) {
                cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, descriptor, null, null).visitEnd();
                return;
            }

            MethodVisitor method = cv.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
            method.visitCode();
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        @Override
        public void visitEnd() {
            // Ended by finish(), once every method of the class has been seen.
        }
    }

    /** Loads the arguments of an old method, all of them, from slot 1. */
    private static void loadOldArguments(MethodVisitor method, Mapping mapping) {
        Type[] parameters = Type.getArgumentTypes(mapping.oldDescriptor());

        for (int i = 0, slot = 1; i < parameters.length; i++) {
            method.visitVarInsn(parameters[i].getOpcode(Opcodes.ILOAD), slot);
            slot += parameters[i].getSize();
        }
    }

    /** Loads the old method's arguments after the graphics, starting at the graphics' slot, for the new method. */
    private static void passNewArguments(MethodVisitor method, Mapping mapping, int graphicsSlot) {
        Type[] parameters = Type.getArgumentTypes(mapping.oldDescriptor());
        Type[] wanted = Type.getArgumentTypes(mapping.newDescriptor());
        int slot = graphicsSlot + 1;

        for (int i = 1; i < wanted.length; i++) {
            if (i < parameters.length) {
                method.visitVarInsn(parameters[i].getOpcode(Opcodes.ILOAD), slot);
                slot += parameters[i].getSize();
            } else {
                // 1.20.1's renderBackground had no mouse position or frame time.
                method.visitInsn(wanted[i].getSort() == Type.FLOAT ? Opcodes.FCONST_0 : Opcodes.ICONST_0);
            }
        }
    }

    private static void enter(MethodVisitor method, int graphicsSlot) {
        method.visitVarInsn(Opcodes.ALOAD, graphicsSlot);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GG, "octo$enter", "()" + NEW, false);
    }

    private static void leave(MethodVisitor method, int graphicsSlot) {
        method.visitVarInsn(Opcodes.ALOAD, graphicsSlot);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GG, "octo$leave", "()V", false);
    }

    private static boolean isGame(String type) {
        return type.startsWith("net/minecraft/") || type.startsWith("com/mojang/");
    }
}
