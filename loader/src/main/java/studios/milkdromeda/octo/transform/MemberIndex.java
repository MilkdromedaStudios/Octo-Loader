package studios.milkdromeda.octo.transform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * What the classes in this launch actually declare, read from their bytes.
 *
 * <p>Answering "does {@code Entity.getX()} exist here" by loading {@code Entity}
 * is the wrong way round when the question is being asked from inside the load
 * of some other class: it drags half the game in early and can recurse into the
 * class being defined. So the hierarchy is read from class files instead —
 * {@code SKIP_CODE}, memoised per class — which is cheap enough to ask about
 * every reference a translated mod makes.
 *
 * <p>Members a mixin adds are not in any class file, so the interfaces mods'
 * mixins give their targets can be registered with {@link #addInterface}; a
 * call through Fabric API's injected {@code FabricItemStack} methods on
 * {@code ItemStack} is then a call to something that exists.
 */
public final class MemberIndex {
    /** One class, as far as linking against it is concerned. */
    public record Shape(String name, String superName, List<String> interfaces, boolean isInterface,
            Set<String> methods, Set<String> fields, Set<String> staticMethods) {
    }

    private static final Shape ABSENT = new Shape("", null, List.of(), false, Set.of(), Set.of(), Set.of());

    private final Function<String, byte[]> classBytes;
    private final Map<String, Shape> shapes = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> addedInterfaces = new ConcurrentHashMap<>();

    /** @param classBytes the class file for an internal name, or {@code null} when nothing has it */
    public MemberIndex(Function<String, byte[]> classBytes) {
        this.classBytes = classBytes;
    }

    /** Records that a mixin makes {@code target} implement {@code iface}. */
    public void addInterface(String target, String iface) {
        addedInterfaces.computeIfAbsent(target, ignored -> ConcurrentHashMap.newKeySet()).add(iface);
    }

    public boolean exists(String internalName) {
        return shape(internalName) != null;
    }

    /** @return whether the class is an interface, or {@code null} when it is not here */
    public Boolean isInterface(String internalName) {
        Shape shape = shape(internalName);
        return shape == null ? null : shape.isInterface();
    }

    /** Whether {@code owner} or anything it inherits from declares this method. */
    public boolean hasMethod(String owner, String name, String descriptor) {
        return findMethodOwner(owner, name + descriptor, new HashSet<>()) != null;
    }

    /** @return whether the method found is static, or {@code null} when it is not found */
    public Boolean isStaticMethod(String owner, String name, String descriptor) {
        Shape declaring = findMethodOwner(owner, name + descriptor, new HashSet<>());
        return declaring == null ? null : declaring.staticMethods().contains(name + descriptor);
    }

    public boolean hasField(String owner, String name, String descriptor) {
        return hasField(owner, name + ":" + descriptor, new HashSet<>());
    }

    /**
     * Whether a type is unknowable rather than absent: an owner whose ancestry
     * leaves the classes this index can read (the JDK, say) may inherit
     * anything, so nothing about it is ever reported missing.
     */
    public boolean isFullyKnown(String owner) {
        return isFullyKnown(owner, new HashSet<>());
    }

    private boolean isFullyKnown(String owner, Set<String> seen) {
        if (owner == null || owner.equals("java/lang/Object") || !seen.add(owner)) {
            return true;
        }

        Shape shape = shape(owner);

        if (shape == null) {
            return false;
        }

        if (!isFullyKnown(shape.superName(), seen)) {
            return false;
        }

        for (String iface : allInterfaces(shape)) {
            if (!isFullyKnown(iface, seen)) {
                return false;
            }
        }

        return true;
    }

    private Shape findMethodOwner(String owner, String key, Set<String> seen) {
        if (owner == null || !seen.add(owner)) {
            return null;
        }

        Shape shape = shape(owner);

        if (shape == null) {
            return null;
        }

        if (shape.methods().contains(key)) {
            return shape;
        }

        Shape found = findMethodOwner(shape.superName(), key, seen);

        if (found != null) {
            return found;
        }

        for (String iface : allInterfaces(shape)) {
            found = findMethodOwner(iface, key, seen);

            if (found != null) {
                return found;
            }
        }

        // Every interface answers Object's methods.
        return shape.isInterface() ? findMethodOwner("java/lang/Object", key, seen) : null;
    }

    private boolean hasField(String owner, String key, Set<String> seen) {
        if (owner == null || !seen.add(owner)) {
            return false;
        }

        Shape shape = shape(owner);

        if (shape == null) {
            return false;
        }

        if (shape.fields().contains(key)) {
            return true;
        }

        if (hasField(shape.superName(), key, seen)) {
            return true;
        }

        for (String iface : allInterfaces(shape)) {
            if (hasField(iface, key, seen)) {
                return true;
            }
        }

        return false;
    }

    private List<String> allInterfaces(Shape shape) {
        Set<String> added = addedInterfaces.get(shape.name());

        if (added == null || added.isEmpty()) {
            return shape.interfaces();
        }

        List<String> out = new ArrayList<>(shape.interfaces());
        out.addAll(added);
        return out;
    }

    /** @return the class's shape, or {@code null} when nothing has it */
    public Shape shape(String internalName) {
        if (internalName == null || internalName.isEmpty() || internalName.charAt(0) == '[') {
            return null;
        }

        Shape shape = shapes.computeIfAbsent(internalName, this::read);
        return shape == ABSENT ? null : shape;
    }

    private Shape read(String internalName) {
        byte[] bytes;

        try {
            bytes = classBytes.apply(internalName);
        } catch (RuntimeException e) {
            bytes = null;
        }

        if (bytes == null) {
            return internalName.startsWith("java/") ? jdkShape(internalName) : ABSENT;
        }

        try {
            Set<String> methods = new LinkedHashSet<>();
            Set<String> staticMethods = new LinkedHashSet<>();
            Set<String> fields = new LinkedHashSet<>();
            String[] header = new String[1];
            List<String> interfaces = new ArrayList<>();
            boolean[] isInterface = new boolean[1];

            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName,
                        String[] declared) {
                    header[0] = superName;
                    isInterface[0] = (access & Opcodes.ACC_INTERFACE) != 0;

                    if (declared != null) {
                        interfaces.addAll(List.of(declared));
                    }
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    methods.add(name + descriptor);

                    if ((access & Opcodes.ACC_STATIC) != 0) {
                        staticMethods.add(name + descriptor);
                    }

                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                        Object value) {
                    fields.add(name + ":" + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            return new Shape(internalName, header[0], List.copyOf(interfaces), isInterface[0], methods, fields,
                    staticMethods);
        } catch (RuntimeException e) {
            return ABSENT;
        }
    }

    /**
     * The JDK's classes are not on the loader's path as files, but they are
     * there: their shape is read by reflection, which for {@code java.*} is
     * always safe.
     */
    private static Shape jdkShape(String internalName) {
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'), false, null);
            Set<String> methods = new LinkedHashSet<>();
            Set<String> staticMethods = new LinkedHashSet<>();
            Set<String> fields = new LinkedHashSet<>();

            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                String key = method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method);
                methods.add(key);

                if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    staticMethods.add(key);
                }
            }

            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                methods.add("<init>" + org.objectweb.asm.Type.getConstructorDescriptor(constructor));
            }

            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                fields.add(field.getName() + ":" + org.objectweb.asm.Type.getDescriptor(field.getType()));
            }

            List<String> interfaces = new ArrayList<>();

            for (Class<?> iface : type.getInterfaces()) {
                interfaces.add(iface.getName().replace('.', '/'));
            }

            return new Shape(internalName, type.getSuperclass() == null ? null
                    : type.getSuperclass().getName().replace('.', '/'), interfaces, type.isInterface(), methods,
                    fields, staticMethods);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return ABSENT;
        }
    }
}
