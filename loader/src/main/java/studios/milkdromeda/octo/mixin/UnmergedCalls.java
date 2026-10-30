package studios.milkdromeda.octo.mixin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Calls to a member a mixin was to add and did not.
 *
 * <p>An accessor mixin adds a method to its target with a name of mixin's own
 * making ({@code catnip$getShaderLightDirections_$md$0ad1e7$0}) and points
 * every use of the accessor at it. When the target no longer has what the
 * accessor reads — 26.x's {@code RenderSystem} has no {@code
 * shaderLightDirections} — the mixin fails, nothing is added, and the uses
 * still point there: each one a {@code NoSuchMethodError}, and Catnip's whole
 * model buffer rendering stopped at the first. Such a call is answered the
 * way a call to a member the game no longer has is: its arguments dropped, a
 * default in place of its result.
 */
public final class UnmergedCalls {
    private static final OctoLog LOG = OctoLog.of(UnmergedCalls.class);
    private static final String MERGED = "$md$";

    private static final Map<String, Boolean> PRESENT = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private UnmergedCalls() {
    }

    /** @return {@code bytes}, with each call to an unmerged member answered by a default */
    static byte[] fix(String className, byte[] bytes, ClassLoader loader) {
        if (bytes == null || loader == null || !contains(bytes)) {
            return bytes;
        }

        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            boolean changed = false;

            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                    AbstractInsnNode next = insn.getNext();

                    if (insn instanceof MethodInsnNode call && call.name.contains(MERGED)
                            && !call.owner.equals(className) && !present(call, loader)) {
                        method.instructions.insert(call, answer(call));
                        method.instructions.remove(call);
                        changed = true;

                        if (REPORTED.add(call.owner + "." + call.name)) {
                            LOG.warn("{} calls {}.{}, which a mixin was to add and did not; the call returns a "
                                    + "default", className.replace('/', '.'), call.owner.replace('/', '.'), call.name);
                        }
                    }

                    insn = next;
                }
            }

            if (!changed) {
                return bytes;
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            LOG.debug("could not check {} for unmerged mixin calls: {}", className, e.toString());
            return bytes;
        }
    }

    /** A cheap scan for the marker before anything is parsed. */
    private static boolean contains(byte[] bytes) {
        outer:
        for (int i = 0; i + 4 <= bytes.length; i++) {
            for (int j = 0; j < 4; j++) {
                if (bytes[i + j] != MERGED.charAt(j)) {
                    continue outer;
                }
            }

            return true;
        }

        return false;
    }

    private static boolean present(MethodInsnNode call, ClassLoader loader) {
        String key = call.owner + "." + call.name + call.desc;
        Boolean known = PRESENT.get(key);

        if (known != null) {
            return known;
        }

        boolean found = true;

        // Only a class already defined is asked: mixin adds to its target as
        // the target loads, so a target not yet loaded may still get it.
        if (loader instanceof studios.milkdromeda.octo.launch.OctoClassLoader octo
                && !octo.isClassLoaded(call.owner.replace('/', '.'))) {
            return true;
        }

        try {
            Class<?> owner = Class.forName(call.owner.replace('/', '.'), false, loader);
            found = false;

            for (Class<?> at = owner; at != null && !found; at = at.getSuperclass()) {
                found = declares(at, call);
            }

            for (Class<?> face : owner.getInterfaces()) {
                found = found || declares(face, call);
            }
        } catch (ClassNotFoundException | LinkageError e) {
            // Not resolvable now: leave the call as it is.
        }

        PRESENT.put(key, found);
        return found;
    }

    private static boolean declares(Class<?> type, MethodInsnNode call) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(call.name) && Type.getMethodDescriptor(method).equals(call.desc)) {
                return true;
            }
        }

        return false;
    }

    /** The arguments and receiver dropped, and the return type's default pushed. */
    private static InsnList answer(MethodInsnNode call) {
        InsnList out = new InsnList();
        Type[] arguments = Type.getArgumentTypes(call.desc);

        for (int i = arguments.length - 1; i >= 0; i--) {
            out.add(new InsnNode(arguments[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }

        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            out.add(new InsnNode(Opcodes.POP));
        }

        switch (Type.getReturnType(call.desc).getSort()) {
            case Type.VOID -> {
            }
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> out.add(new InsnNode(Opcodes.ICONST_0));
            case Type.LONG -> out.add(new InsnNode(Opcodes.LCONST_0));
            case Type.FLOAT -> out.add(new InsnNode(Opcodes.FCONST_0));
            case Type.DOUBLE -> out.add(new InsnNode(Opcodes.DCONST_0));
            default -> {
                Type returned = Type.getReturnType(call.desc);
                out.add(new LdcInsnNode(returned.getDescriptor()));
                out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/mixin/UnmergedCalls",
                        "value", "(Ljava/lang/String;)Ljava/lang/Object;", false));
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, returned.getSort() == Type.ARRAY
                        ? returned.getDescriptor() : returned.getInternalName()));
            }
        }

        return out;
    }

    /**
     * What an unmerged accessor answers, by its type: an array with nothing
     * missing from it, and for the light directions a renderer reads, the
     * two the game lights its world with.
     */
    public static Object value(String descriptor) {
        Type type = Type.getType(descriptor);

        if (type.getSort() != Type.ARRAY || type.getDimensions() != 1) {
            return null;
        }

        try {
            Class<?> component = Class.forName(type.getElementType().getClassName(), true,
                    OctoMixinService.loader());

            if (component.getName().equals("org.joml.Vector3f")) {
                Object lights = java.lang.reflect.Array.newInstance(component, 2);
                java.lang.reflect.Constructor<?> vector = component.getConstructor(float.class, float.class,
                        float.class);
                java.lang.reflect.Array.set(lights, 0, normalize(vector.newInstance(0.2f, 1.0f, -0.7f)));
                java.lang.reflect.Array.set(lights, 1, normalize(vector.newInstance(-0.2f, 1.0f, 0.7f)));
                return lights;
            }

            return java.lang.reflect.Array.newInstance(component, 0);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static Object normalize(Object vector) throws ReflectiveOperationException {
        return vector.getClass().getMethod("normalize").invoke(vector);
    }
}
