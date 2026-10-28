package studios.milkdromeda.octo.transform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Gives a translated mod's class the methods the game's types made abstract
 * after the mod was written.
 *
 * <p>A class that does not implement an abstract method still loads; the
 * method fails, with an {@code AbstractMethodError}, when the game calls it.
 * 26.x's {@code Recipe} added {@code assemble(input)} without the registries,
 * {@code group()}, {@code placementInfo()}, {@code recipeBookCategory()} and
 * {@code showNotification()}, and the game asks every recipe it loads for
 * them — Create's would have stopped the data pack reload, and the server.
 *
 * <p>Each one missing is answered, in order of preference, by:
 * <ul>
 * <li>the class's own method of that name taking one argument more —
 * {@code assemble(input, registries)} — with the registries, or a default,
 * filled in;</li>
 * <li>its own method under the old name: {@code getGroup()} for
 * {@code group()}, and the other way round;</li>
 * <li>a value that leaves the game as it would be without the mod's answer:
 * no placement, the miscellaneous book category, an empty string, a new
 * object of the game's where it has a plain constructor (a block entity's
 * render state, which 26.x asks every renderer for), false, zero, an empty
 * collection or null.</li>
 * </ul>
 */
public final class AbstractGaps implements Transformer {
    private static final OctoLog LOG = OctoLog.of(AbstractGaps.class);
    private static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String RECIPE = "net/minecraft/world/item/crafting/Recipe";

    /** Answers for particular methods, where the type's default would be wrong. */
    private static final Map<String, Answer> ANSWERS = Map.of(
            RECIPE + ".showNotification()Z", method -> {
                method.visitInsn(Opcodes.ICONST_1);
            },
            RECIPE + ".placementInfo()Lnet/minecraft/world/item/crafting/PlacementInfo;", method ->
                    method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/item/crafting/PlacementInfo",
                            "NOT_PLACEABLE", "Lnet/minecraft/world/item/crafting/PlacementInfo;"),
            RECIPE + ".recipeBookCategory()Lnet/minecraft/world/item/crafting/RecipeBookCategory;", method ->
                    method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/item/crafting/RecipeBookCategories",
                            "CRAFTING_MISC", "Lnet/minecraft/world/item/crafting/RecipeBookCategory;"));

    private interface Answer {
        void push(MethodVisitor method);
    }

    private final MemberIndex index;

    public AbstractGaps(MemberIndex index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "abstract-gaps";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);

        if ((reader.getAccess() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0
                || reader.getSuperName() == null) {
            return bytes;
        }

        Map<String, Integer> own = new HashMap<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                own.put(name + descriptor, access);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // The superclasses, then every interface any of them has.
        List<MemberIndex.Shape> chain = new ArrayList<>();

        for (String type = reader.getSuperName(); type != null; ) {
            MemberIndex.Shape shape = index.shape(type);

            if (shape == null) {
                // A hierarchy that cannot be read whole may implement anything.
                return bytes;
            }

            chain.add(shape);
            type = shape.superName();
        }

        List<MemberIndex.Shape> interfaces = interfaces(reader.getInterfaces(), chain);

        if (interfaces == null) {
            return bytes;
        }

        Map<String, String> missing = new LinkedHashMap<>();

        for (MemberIndex.Shape type : concat(chain, interfaces)) {
            if (!isGame(type.name())) {
                continue;
            }

            for (String method : type.methods()) {
                Integer access = type.access().get(method);

                if (access != null && (access & Opcodes.ACC_ABSTRACT) != 0 && (access & Opcodes.ACC_STATIC) == 0
                        && !implemented(method, own, chain, interfaces)) {
                    missing.putIfAbsent(method, type.name());
                }
            }
        }

        if (missing.isEmpty()) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        List<String> answered = new ArrayList<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                missing.forEach((method, owner) -> answered.add(write(cv, className, owner, method, own, chain)));
                super.visitEnd();
            }
        }, 0);

        LOG.debug("{} answers what the game made abstract since: {}", className.replace('/', '.'), answered);
        return writer.toByteArray();
    }

    /** Every interface of the class and of its superclasses, and theirs; {@code null} if one cannot be read. */
    private List<MemberIndex.Shape> interfaces(String[] own, List<MemberIndex.Shape> chain) {
        Deque<String> pending = new ArrayDeque<>(List.of(own));
        chain.forEach(shape -> pending.addAll(shape.interfaces()));
        Set<String> seen = new LinkedHashSet<>();
        List<MemberIndex.Shape> out = new ArrayList<>();

        while (!pending.isEmpty()) {
            String name = pending.poll();

            if (!seen.add(name)) {
                continue;
            }

            MemberIndex.Shape shape = index.shape(name);

            if (shape == null) {
                return null;
            }

            out.add(shape);
            pending.addAll(shape.interfaces());
        }

        return out;
    }

    private static boolean implemented(String method, Map<String, Integer> own, List<MemberIndex.Shape> chain,
            List<MemberIndex.Shape> interfaces) {
        Integer mine = own.get(method);

        if (mine != null && (mine & Opcodes.ACC_ABSTRACT) == 0) {
            return true;
        }

        for (MemberIndex.Shape type : concat(chain, interfaces)) {
            Integer access = type.access().get(method);

            if (access != null && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0) {
                return true;
            }
        }

        return false;
    }

    /** Writes the answer, and says which it was. */
    private String write(ClassVisitor writer, String className, String owner, String method,
            Map<String, Integer> own, List<MemberIndex.Shape> chain) {
        int split = method.indexOf('(');
        String name = method.substring(0, split);
        String descriptor = method.substring(split);
        Type[] arguments = Type.getArgumentTypes(descriptor);
        Type returned = Type.getReturnType(descriptor);

        MethodVisitor out = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        out.visitCode();
        String how;
        String wider = widerOverload(name, descriptor, own, chain);
        String renamed = wider == null ? renamed(name, descriptor, own, chain) : null;

        if (wider != null || renamed != null) {
            out.visitVarInsn(Opcodes.ALOAD, 0);
            int slot = 1;

            for (Type argument : arguments) {
                out.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
                slot += argument.getSize();
            }

            String target = descriptor;

            if (wider != null) {
                target = wider;
                Type extra = Type.getArgumentTypes(wider)[arguments.length];

                if (extra.getDescriptor().equals("L" + PROVIDER + ";")) {
                    out.visitVarInsn(Opcodes.ALOAD, 0);
                    out.visitInsn(Opcodes.ACONST_NULL);
                    out.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/bridge/ValueIo", "registries",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                    out.visitTypeInsn(Opcodes.CHECKCAST, PROVIDER);
                } else {
                    Nothing.push(out, extra);
                }
            }

            String callee = renamed != null ? renamed : name;
            out.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, callee, target, false);
            how = name + " -> " + callee + target;
        } else {
            Answer answer = ANSWERS.get(owner + "." + method);

            if (answer != null) {
                answer.push(out);
            } else if (returned.getDescriptor().equals("Ljava/lang/String;")) {
                out.visitLdcInsn("");
            } else if (isMakeable(returned)) {
                // A fresh one rather than null: 26.x asks every block entity
                // renderer for a render state, and uses what it is given.
                out.visitTypeInsn(Opcodes.NEW, returned.getInternalName());
                out.visitInsn(Opcodes.DUP);
                out.visitMethodInsn(Opcodes.INVOKESPECIAL, returned.getInternalName(), "<init>", "()V", false);
            } else {
                Nothing.push(out, returned);
            }

            how = name + " -> default";
        }

        out.visitInsn(returned.getOpcode(Opcodes.IRETURN));
        out.visitMaxs(0, 0);
        out.visitEnd();
        return how;
    }

    /** A game class that is concrete and has a public constructor taking nothing. */
    private boolean isMakeable(Type type) {
        if (type.getSort() != Type.OBJECT || !isGame(type.getInternalName())) {
            return false;
        }

        MemberIndex.Shape shape = index.shape(type.getInternalName());
        Integer constructor = shape == null ? null : shape.access().get("<init>()V");
        return shape != null && shape.isConcrete() && constructor != null && (constructor & Opcodes.ACC_PUBLIC) != 0;
    }

    /** The class's own {@code name} with this method's arguments and one more after them. */
    private static String widerOverload(String name, String descriptor, Map<String, Integer> own,
            List<MemberIndex.Shape> chain) {
        String arguments = descriptor.substring(1, descriptor.indexOf(')'));
        String returned = descriptor.substring(descriptor.indexOf(')') + 1);

        for (String candidate : concrete(own, chain)) {
            if (!candidate.startsWith(name + "(")) {
                continue;
            }

            String candidateDescriptor = candidate.substring(name.length());
            Type[] candidateArguments = Type.getArgumentTypes(candidateDescriptor);

            if (candidateDescriptor.startsWith("(" + arguments)
                    && candidateDescriptor.endsWith(")" + returned)
                    && candidateArguments.length == Type.getArgumentTypes(descriptor).length + 1) {
                return candidateDescriptor;
            }
        }

        return null;
    }

    /** The class's own method of the same shape under the old name: {@code getGroup} for {@code group}. */
    private static String renamed(String name, String descriptor, Map<String, Integer> own,
            List<MemberIndex.Shape> chain) {
        List<String> names = new ArrayList<>();
        String capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        names.add("get" + capitalised);
        names.add("is" + capitalised);

        for (String prefix : List.of("get", "is")) {
            if (name.length() > prefix.length() && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                names.add(Character.toLowerCase(name.charAt(prefix.length())) + name.substring(prefix.length() + 1));
            }
        }

        Set<String> available = concrete(own, chain);

        for (String candidate : names) {
            if (available.contains(candidate + descriptor)) {
                return candidate;
            }
        }

        return null;
    }

    /** The instance methods with code the class has, its own and those it inherits from its superclasses. */
    private static Set<String> concrete(Map<String, Integer> own, List<MemberIndex.Shape> chain) {
        Set<String> out = new LinkedHashSet<>();
        own.forEach((method, access) -> {
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0) {
                out.add(method);
            }
        });

        for (MemberIndex.Shape type : chain) {
            for (String method : type.methods()) {
                Integer access = type.access().get(method);

                if (access != null && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE))
                        == 0) {
                    out.add(method);
                }
            }
        }

        return out;
    }

    private static List<MemberIndex.Shape> concat(List<MemberIndex.Shape> first, List<MemberIndex.Shape> second) {
        List<MemberIndex.Shape> out = new ArrayList<>(first);
        out.addAll(second);
        return out;
    }

    private static boolean isGame(String type) {
        return type.startsWith("net/minecraft/") || type.startsWith("com/mojang/");
    }
}
