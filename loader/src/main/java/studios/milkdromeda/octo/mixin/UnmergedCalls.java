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

    private static final Map<String, Method> TARGETS = new ConcurrentHashMap<>();

    /**
     * An invoker made an ordinary method that calls its target by
     * reflection: the annotation removed, so mixin does not try to generate
     * it, and synthetic, so the interface stays an accessor mixin.
     */
    static void retireToCall(MethodNode method, org.objectweb.asm.tree.AnnotationNode invoker, String owner,
            String name, String targetDescriptor) {
        if (method.visibleAnnotations != null) {
            method.visibleAnnotations.remove(invoker);
        }

        if (method.invisibleAnnotations != null) {
            method.invisibleAnnotations.remove(invoker);
        }

        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type returned = Type.getReturnType(method.desc);
        InsnList body = new InsnList();
        body.add(new InsnNode(isStatic ? Opcodes.ACONST_NULL : Opcodes.NOP));

        if (!isStatic) {
            body.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        }

        body.add(new LdcInsnNode(owner));
        body.add(new LdcInsnNode(name));
        body.add(new LdcInsnNode(targetDescriptor));
        body.add(new LdcInsnNode(returned.getDescriptor()));
        body.add(new LdcInsnNode(arguments.length));
        body.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int slot = isStatic ? 0 : 1;

        for (int i = 0; i < arguments.length; i++) {
            body.add(new InsnNode(Opcodes.DUP));
            body.add(new LdcInsnNode(i));
            body.add(new org.objectweb.asm.tree.VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), slot));
            box(body, arguments[i]);
            body.add(new InsnNode(Opcodes.AASTORE));
            slot += arguments[i].getSize();
        }

        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/mixin/UnmergedCalls", "call",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                        + "[Ljava/lang/Object;)Ljava/lang/Object;", false));
        unbox(body, returned);
        body.add(new InsnNode(returned.getOpcode(Opcodes.IRETURN)));

        method.access = (method.access & ~Opcodes.ACC_ABSTRACT) | Opcodes.ACC_SYNTHETIC;
        method.instructions = body;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.maxStack = 10;
        method.maxLocals = slot;
    }

    private static void box(InsnList body, Type type) {
        String boxed = switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.CHAR -> "java/lang/Character";
            case Type.BYTE -> "java/lang/Byte";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };

        if (boxed != null) {
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, boxed, "valueOf",
                    "(" + type.getDescriptor() + ")L" + boxed + ";", false));
        }
    }

    private static void unbox(InsnList body, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> body.add(new InsnNode(Opcodes.POP));
            case Type.BOOLEAN -> {
                body.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z",
                        false));
            }
            case Type.CHAR -> {
                body.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C",
                        false));
            }
            case Type.BYTE, Type.SHORT, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE -> {
                body.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
                String unboxer = switch (type.getSort()) {
                    case Type.BYTE -> "byteValue";
                    case Type.SHORT -> "shortValue";
                    case Type.INT -> "intValue";
                    case Type.LONG -> "longValue";
                    case Type.FLOAT -> "floatValue";
                    default -> "doubleValue";
                };
                body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", unboxer,
                        "()" + type.getDescriptor(), false));
            }
            default -> body.add(new TypeInsnNode(Opcodes.CHECKCAST,
                    type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName()));
        }
    }

    /**
     * What a retired invoker calls: the target's method, found by name and
     * descriptor, called with the invoker's arguments. When they do not fit
     * — a client level where the method now wants a server's — it answers
     * what a missing member does.
     */
    public static Object call(Object self, String owner, String name, String descriptor, String returned,
            Object[] arguments) {
        try {
            ClassLoader loader = self != null ? self.getClass().getClassLoader() : OctoMixinService.loader();
            Method target = TARGETS.computeIfAbsent(owner + "." + name + descriptor, key -> find(loader, owner,
                    name, descriptor));

            if (target != null) {
                return target.invoke(self, arguments);
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();

            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new IllegalStateException(cause);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            if (REPORTED.add("call:" + owner + "." + name)) {
                LOG.warn("{}.{} could not be called with what its old invoker was given: {}",
                        owner.replace('/', '.'), name, e.toString());
            }
        }

        return defaultFor(returned);
    }

    private static Method find(ClassLoader loader, String owner, String name, String descriptor) {
        try {
            for (Class<?> at = Class.forName(owner.replace('/', '.'), false, loader); at != null;
                    at = at.getSuperclass()) {
                for (Method method : at.getDeclaredMethods()) {
                    if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(descriptor)) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            }
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            LOG.debug("could not find {}.{}{}: {}", owner, name, descriptor, e.toString());
        }

        return null;
    }

    private static Object defaultFor(String descriptor) {
        return switch (descriptor.charAt(0)) {
            case 'Z' -> Boolean.FALSE;
            case 'C' -> Character.valueOf((char) 0);
            case 'B' -> Byte.valueOf((byte) 0);
            case 'S' -> Short.valueOf((short) 0);
            case 'I' -> Integer.valueOf(0);
            case 'J' -> Long.valueOf(0);
            case 'F' -> Float.valueOf(0);
            case 'D' -> Double.valueOf(0);
            case 'V' -> null;
            default -> value(descriptor);
        };
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
