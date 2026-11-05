package studios.milkdromeda.octo.mixin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import studios.milkdromeda.octo.compat.api.Equivalents;
import studios.milkdromeda.octo.transform.ByteScan;
import studios.milkdromeda.octo.transform.TransformContext;
import studios.milkdromeda.octo.transform.Transformer;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Aims a mod's mixins at the class this Minecraft actually has.
 *
 * <p>A mixin names its target by hand — {@code @Inject(method = "tick")},
 * {@code @Accessor("entries")} — and mixin resolves that name against the real
 * class at apply time. When the name is right for the version the mod was built
 * against and wrong for the one running, the result is not a mixin that quietly
 * does nothing: it is a "Critical injection failure" that stops the launch, and
 * it stops it on behalf of a mod that may only have wanted to change a tooltip.
 *
 * <p>Three kinds of drift account for nearly all of it, and all three can be
 * settled by looking at the target class instead of guessing:
 *
 * <ul>
 *   <li><b>Compiler-numbered lambdas.</b> {@code lambda$reloadResourcePacks$21}
 *       is not a name Mojang chose; it is a counter, and it moves when anything
 *       earlier in the file changes. The stable part is the enclosing method, so
 *       that is what is matched on.</li>
 *   <li><b>A member that was renamed.</b> Answered from the compatibility table,
 *       and failing that from the shape: a field of the exact descriptor the
 *       accessor needs, unique on the target, is that field whatever this
 *       version happens to call it.</li>
 *   <li><b>A descriptor that drifted</b> while the name stayed. If exactly one
 *       method on the target carries the name, the descriptor is dropped rather
 *       than being allowed to fail the match.</li>
 * </ul>
 *
 * <p>What is left over — a target that genuinely is not there in any form — is
 * marked optional instead of being left to abort the launch. The mod loses that
 * one injection and keeps everything else, which is the trade every other part
 * of Octo makes too.
 */
public final class MixinTargets implements Transformer {
    private static final OctoLog LOG = OctoLog.of(MixinTargets.class);

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String PSEUDO = "Lorg/spongepowered/asm/mixin/Pseudo;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    /**
     * Every annotation whose {@code method} names a member of the target class.
     *
     * <p>Mixin's own injectors and MixinExtras', which mods now reach for as
     * freely as the built-in ones and which fail in exactly the same way.
     */
    private static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final Set<String> SHADOW_COMPANIONS = Set.of(SHADOW, "Lorg/spongepowered/asm/mixin/Final;",
            "Lorg/spongepowered/asm/mixin/Mutable;");

    /** How far up a target's superclasses to look for an inherited member. */
    private static final int MAX_HIERARCHY_DEPTH = 16;

    private final Equivalents table;
    private final Function<String, byte[]> classBytes;
    private final ByteScan carriesMixin = new ByteScan(List.of(MIXIN));

    /**
     * Reading a target class goes back through the class loader, and the class
     * loader runs this transformer. Without this, the first mixin that targets a
     * class the loader has to transform recurses until the stack runs out.
     */
    private final ThreadLocal<Boolean> resolving = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Set<String> adjustments = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * @param classBytes reads a class by internal name as it stands after
     *                   translation, or {@code null} when nothing has it
     */
    public MixinTargets(Equivalents table, Function<String, byte[]> classBytes) {
        this.table = table;
        this.classBytes = classBytes;
    }

    /** Every target this had to re-aim or give up on, for the launch summary. */
    public Set<String> adjustments() {
        return new LinkedHashSet<>(adjustments);
    }

    @Override
    public String name() {
        return "mixin-targets";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        // The game's own classes are never mixins, and skipping them here is what
        // keeps the scan below off the bulk of a launch.
        return !resolving.get() && !className.startsWith("net/minecraft/") && !className.startsWith("com/mojang/");
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        if (!carriesMixin.matches(bytes)) {
            return bytes;
        }

        try {
            return adapt(className, bytes, context);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            // A mixin this could not read is one mixin can still try for itself.
            LOG.debug("could not check the targets of {}: {}", className.replace('/', '.'), e.toString());
            return bytes;
        }
    }

    private byte[] adapt(String className, byte[] bytes, TransformContext context) {
        ClassNode mixin = new ClassNode();
        new ClassReader(bytes).accept(mixin, 0);

        AnnotationNode declaration = annotation(mixin.invisibleAnnotations, MIXIN);

        if (declaration == null) {
            declaration = annotation(mixin.visibleAnnotations, MIXIN);
        }

        if (declaration == null) {
            return bytes;
        }

        List<Target> targets = readTargets(declaration);

        if (targets.isEmpty()) {
            // A mixin that names targets none of which exist: a client class on a
            // dedicated server, or a class this version removed. Mixin's answer is
            // an InvalidMixinException during prepare, and in a required config
            // that aborts the prepare of every config after it — one missing HUD
            // class cost Fabric API its registry sync. Mixin's own @Pseudo says
            // "skip me if the target is not there", which is exactly this case.
            if (!targetNames(declaration).isEmpty() && !isPseudo(mixin)) {
                if (mixin.invisibleAnnotations == null) {
                    mixin.invisibleAnnotations = new ArrayList<>();
                }

                mixin.invisibleAnnotations.add(new AnnotationNode(PSEUDO));
                note(context, className, "none of its targets " + targetNames(declaration)
                        + " exist here, so it is skipped instead of failing its config");
                ClassWriter writer = new ClassWriter(0);
                mixin.accept(writer);
                return writer.toByteArray();
            }

            // Otherwise an abstract base that real mixins extend, with nothing
            // to check.
            return bytes;
        }

        // What the mixin adds to its target is part of the target by the time
        // the injectors run: Lithium adds getHeldItem() to EntityCollisionContext
        // and then injects into it. Without these, that injector looked aimed at
        // nothing and was switched off.
        List<Member> added = new ArrayList<>();

        for (MethodNode method : mixin.methods) {
            if (!method.name.startsWith("<") && addsToTarget(method)) {
                added.add(new Member(method.name, method.desc));
            }
        }

        if (!added.isEmpty()) {
            List<Target> merged = new ArrayList<>(targets.size());

            for (Target target : targets) {
                List<Member> methods = new ArrayList<>(target.methods());
                methods.addAll(added);
                merged.add(new Target(target.name(), methods, target.fields()));
            }

            targets = merged;
        }

        boolean changed = false;

        List<MethodNode> adapters = new ArrayList<>();

        for (MethodNode method : mixin.methods) {
            changed |= adaptInjectors(mixin, adapters, className, method, targets, context);
            changed |= adaptAccessor(className, method, targets, context);
            changed |= adaptShadowMethod(className, method, targets, context);
        }

        mixin.methods.addAll(adapters);

        for (FieldNode field : mixin.fields) {
            changed |= adaptShadowField(mixin, className, field, targets, context);
        }

        if (!changed) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(0);
        mixin.accept(writer);
        return writer.toByteArray();
    }

    // ------------------------------------------------------------------ targets

    /**
     * Whether a mixin method arrives in the target under its own name. A
     * shadow is the target's already; an injector's handler is merged under a
     * generated name, and is very often named after the method it injects
     * into — Collective's {@code withConfirmedWarning} handler counted as the
     * target's {@code withConfirmedWarning}, and its injector was aimed at
     * itself.
     */
    private static boolean addsToTarget(MethodNode method) {
        for (AnnotationNode annotation : allAnnotations(method)) {
            if (annotation.desc.equals(SHADOW) || annotation.desc.equals(OVERWRITE)
                    || annotation.desc.equals(ACCESSOR) || annotation.desc.equals(INVOKER)
                    || INJECTORS.contains(annotation.desc)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isPseudo(ClassNode mixin) {
        return annotation(mixin.invisibleAnnotations, PSEUDO) != null
                || annotation(mixin.visibleAnnotations, PSEUDO) != null;
    }

    private List<Target> readTargets(AnnotationNode declaration) {
        List<Target> out = new ArrayList<>();

        for (String name : targetNames(declaration)) {
            Target target = shapeOf(name);

            if (target != null) {
                out.add(target);
            }
        }

        return out;
    }

    private static Set<String> targetNames(AnnotationNode declaration) {
        Set<String> names = new LinkedHashSet<>();

        if (value(declaration, "value") instanceof List<?> types) {
            for (Object type : types) {
                if (type instanceof Type asType) {
                    names.add(asType.getInternalName());
                }
            }
        }

        if (value(declaration, "targets") instanceof List<?> declared) {
            for (Object name : declared) {
                if (name instanceof String text) {
                    names.add(text.replace('.', '/'));
                }
            }
        }

        return names;
    }

    /**
     * Everything a target declares, plus everything it inherits.
     *
     * <p>Inherited members matter as much as declared ones: a mixin shadowing
     * {@code minecraft} on a screen subclass is shadowing a field declared three
     * classes up, and treating that as absent would have this class "fix" a
     * mixin that was never broken.
     */
    private Target shapeOf(String internalName) {
        resolving.set(Boolean.TRUE);

        try {
            List<Member> methods = new ArrayList<>();
            List<Member> fields = new ArrayList<>();
            String current = internalName;

            for (int depth = 0; current != null && depth < MAX_HIERARCHY_DEPTH; depth++) {
                ClassNode node = read(current);

                if (node == null) {
                    return depth == 0 ? null : new Target(internalName, methods, fields);
                }

                for (MethodNode method : node.methods) {
                    methods.add(new Member(method.name, method.desc));
                }

                for (FieldNode field : node.fields) {
                    fields.add(new Member(field.name, field.desc));
                }

                current = "java/lang/Object".equals(node.superName) ? null : node.superName;
            }

            return new Target(internalName, methods, fields);
        } finally {
            resolving.set(Boolean.FALSE);
        }
    }

    private ClassNode read(String internalName) {
        try {
            byte[] bytes = classBytes.apply(internalName);

            if (bytes == null) {
                return null;
            }

            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            return node;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return null;
        }
    }

    // --------------------------------------------------------------- injectors

    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

    /**
     * Aims an injection into a constructor a loader's patch lengthened at the
     * game's own, which takes the first of its arguments.
     *
     * <p>NeoForge gives the game's quads two more record components, and so a
     * longer constructor, which its vanilla-shaped one hands defaults to.
     * Sodium works out each quad's normal and facing by injecting into the
     * longer one; on the unpatched game nothing matched, the injection was
     * skipped, and the first chunk Sodium built read a facing that was never
     * set. Injected into the game's constructor instead, through a handler
     * that passes on what it is given and the patch's defaults for the rest,
     * the mod's handler runs for every quad as it did.
     */
    private boolean adaptToShorterConstructor(ClassNode mixin, List<MethodNode> adapters, String className,
            MethodNode handler, AnnotationNode annotation, String spec, List<Target> targets,
            TransformContext context) {
        MemberRef reference = MemberRef.parse(spec);

        if (!INJECT.equals(annotation.desc) || reference == null || !"<init>".equals(reference.name())
                || reference.descriptor() == null || (handler.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) {
            return false;
        }

        for (Target target : targets) {
            if (target.hasMethod("<init>", reference.descriptor())) {
                return false;
            }
        }

        Type[] wanted = Type.getArgumentTypes(reference.descriptor());
        Type[] handled = Type.getArgumentTypes(handler.desc);

        // Only a handler that takes exactly the constructor's arguments and its
        // callback: one that also captures locals is aimed at code the game's
        // constructor does not have.
        if (handled.length != wanted.length + 1 || !handled[wanted.length].getDescriptor().equals(CALLBACK_INFO)) {
            return false;
        }

        String shorter = null;
        int longest = -1;

        for (Target target : targets) {
            for (Member member : target.methods()) {
                if (!member.name().equals("<init>")) {
                    continue;
                }

                Type[] offered = Type.getArgumentTypes(member.descriptor());

                if (offered.length < wanted.length && offered.length > longest
                        && java.util.Arrays.equals(offered, java.util.Arrays.copyOf(wanted, offered.length))) {
                    shorter = member.descriptor();
                    longest = offered.length;
                }
            }
        }

        if (shorter == null) {
            return false;
        }

        Type[] given = Type.getArgumentTypes(shorter);
        Type[] adapterArguments = java.util.Arrays.copyOf(given, given.length + 1);
        adapterArguments[given.length] = Type.getType(CALLBACK_INFO);
        MethodNode adapter = new MethodNode(org.objectweb.asm.Opcodes.ASM9,
                org.objectweb.asm.Opcodes.ACC_PRIVATE, handler.name + "$octo$" + given.length, Type.getMethodDescriptor(Type.VOID_TYPE, adapterArguments),
                null, null);

        adapter.visitCode();
        adapter.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        int slot = 1;

        for (Type argument : given) {
            adapter.visitVarInsn(argument.getOpcode(org.objectweb.asm.Opcodes.ILOAD), slot);
            slot += argument.getSize();
        }

        for (int i = given.length; i < wanted.length; i++) {
            pushDefault(adapter, wanted[i]);
        }

        adapter.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, slot);
        boolean isPrivate = (handler.access & org.objectweb.asm.Opcodes.ACC_PRIVATE) != 0;
        adapter.visitMethodInsn(isPrivate ? org.objectweb.asm.Opcodes.INVOKESPECIAL
                : org.objectweb.asm.Opcodes.INVOKEVIRTUAL, mixin.name, handler.name, handler.desc, false);
        adapter.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        adapter.visitMaxs(slot + wanted.length * 2 + 2, slot + 1);
        adapter.visitEnd();

        // The injection moves to the adapter, aimed at the game's constructor.
        if (handler.visibleAnnotations != null && handler.visibleAnnotations.remove(annotation)) {
            adapter.visibleAnnotations = new ArrayList<>(List.of(annotation));
        } else if (handler.invisibleAnnotations != null && handler.invisibleAnnotations.remove(annotation)) {
            adapter.invisibleAnnotations = new ArrayList<>(List.of(annotation));
        } else {
            return false;
        }

        put(annotation, "method", List.of("<init>" + shorter));
        adapters.add(adapter);
        note(context, className, handler.name + ": " + spec + " is not in this version, so it injects into <init>"
                + shorter + " with the rest of its arguments at their defaults");
        return true;
    }

    /** The value a missing argument takes: its type's own default constant, or zero, false or null. */
    private void pushDefault(MethodNode method, Type type) {
        switch (type.getSort()) {
            case Type.LONG -> method.visitInsn(org.objectweb.asm.Opcodes.LCONST_0);
            case Type.FLOAT -> method.visitInsn(org.objectweb.asm.Opcodes.FCONST_0);
            case Type.DOUBLE -> method.visitInsn(org.objectweb.asm.Opcodes.DCONST_0);
            case Type.OBJECT -> {
                String constant = typeDefault(type);

                if (constant != null) {
                    method.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, type.getInternalName(), constant,
                            type.getDescriptor());
                } else {
                    method.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
                }
            }
            case Type.ARRAY -> method.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
            default -> method.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
        }
    }

    /** The name of the constant a type gives for "nothing particular", or {@code null} when it has none. */
    private String typeDefault(Type type) {
        String internalName = type.getInternalName();

        if (internalName.startsWith("java/")) {
            return null;
        }

        ClassNode node = read(internalName);

        if (node == null) {
            return null;
        }

        int constant = org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC
                | org.objectweb.asm.Opcodes.ACC_FINAL;

        for (String name : TYPE_DEFAULTS) {
            for (FieldNode candidate : node.fields) {
                if (candidate.name.equals(name) && candidate.desc.equals(type.getDescriptor())
                        && (candidate.access & constant) == constant) {
                    return name;
                }
            }
        }

        return null;
    }

    private boolean adaptInjectors(ClassNode mixin, List<MethodNode> adapters, String className, MethodNode method,
            List<Target> targets, TransformContext context) {
        boolean changed = false;

        for (AnnotationNode annotation : allAnnotations(method)) {
            if (INJECTORS.contains(annotation.desc)) {
                changed |= adaptInjector(mixin, adapters, className, method, annotation, targets, context);
            }
        }

        return changed;
    }

    private boolean adaptInjector(ClassNode mixin, List<MethodNode> adapters, String className, MethodNode method,
            AnnotationNode annotation, List<Target> targets, TransformContext context) {
        List<String> specs = strings(value(annotation, "method"));

        if (specs.isEmpty()) {
            // Either a @Desc-based target, which mixin resolves structurally and
            // does not need help with, or a form this cannot read. Left alone.
            return false;
        }

        // A constructor a loader's patch lengthened: aimed at the game's shorter
        // one before anything else, as dropping the descriptor would leave a
        // handler taking arguments that constructor does not have.
        if (specs.size() == 1 && adaptToShorterConstructor(mixin, adapters, className, method, annotation,
                specs.get(0), targets, context)) {
            return true;
        }

        List<String> rewritten = new ArrayList<>(specs);
        boolean changed = false;
        boolean anyResolved = false;

        for (int i = 0; i < specs.size(); i++) {
            MemberRef reference = MemberRef.parse(specs.get(i));

            if (reference == null) {
                // A wildcard or a selector this does not parse. Assumed to match,
                // because assuming otherwise would mark a working injector
                // optional and lose it for no reason.
                anyResolved = true;
                continue;
            }

            String resolved = resolveMethod(reference, targets);

            if (resolved == null) {
                continue;
            }

            anyResolved = true;

            if (!resolved.equals(specs.get(i))) {
                rewritten.set(i, resolved);
                changed = true;
                note(context, className, method.name + ": " + specs.get(i) + " is not in this version, so it "
                        + "injects into " + resolved + " instead");
            }
        }

        if (changed) {
            put(annotation, "method", rewritten);
        }

        if (anyResolved || isOptional(annotation)) {
            return changed;
        }

        // Nothing named here exists on the target under any spelling. Mixin's
        // answer to that is to abort the launch; Octo's is to lose the injection
        // and keep the mod, so the injector is marked optional instead.
        put(annotation, "require", 0);
        put(annotation, "expect", 0);
        note(context, className, method.name + ": nothing matching " + String.join(", ", specs)
                + " exists in this version, so that injection is skipped rather than failing the launch");
        return true;
    }

    private static boolean isOptional(AnnotationNode annotation) {
        return value(annotation, "require") instanceof Integer require && require == 0;
    }

    /**
     * @return the specification to use instead — possibly the one given — or
     *         {@code null} when the target has nothing resembling it
     */
    private String resolveMethod(MemberRef reference, List<Target> targets) {
        for (Target target : targets) {
            if (target.hasMethod(reference.name(), reference.descriptor())) {
                return reference.original();
            }
        }

        for (Target target : targets) {
            for (String alias : table.methodAliases(target.name(), reference.name())) {
                if (target.hasMethod(alias, reference.descriptor())) {
                    return reference.rename(alias);
                }
            }
        }

        String lambda = resolveLambda(reference, targets);

        if (lambda != null) {
            return reference.rename(lambda);
        }

        // The name is right and the descriptor is not. One method of that name on
        // the target leaves no ambiguity to protect against, so the descriptor is
        // dropped rather than allowed to fail the match.
        if (reference.descriptor() != null) {
            for (Target target : targets) {
                if (target.countMethods(reference.name()) == 1) {
                    return reference.name();
                }
            }
        }

        return null;
    }

    /**
     * Matches a lambda by the method it was written inside.
     *
     * <p>{@code lambda$reloadResourcePacks$21} names a synthetic method the
     * compiler numbered, and the number counts the lambdas that came before it in
     * the file. Mojang adding one three methods earlier renumbers this one, so
     * the number is the least stable part of the name and the enclosing method is
     * the most.
     */
    private static String resolveLambda(MemberRef reference, List<Target> targets) {
        String name = reference.name();

        if (!name.startsWith("lambda$")) {
            return null;
        }

        int lastDollar = name.lastIndexOf('$');

        if (lastDollar <= "lambda$".length()) {
            return null;
        }

        String prefix = name.substring(0, lastDollar + 1);
        Set<String> byName = new LinkedHashSet<>();
        Set<String> byDescriptor = new LinkedHashSet<>();

        for (Target target : targets) {
            for (Member method : target.methods()) {
                if (!method.name().startsWith(prefix)) {
                    continue;
                }

                byName.add(method.name());

                if (method.descriptor().equals(reference.descriptor())) {
                    byDescriptor.add(method.name());
                }
            }
        }

        if (byDescriptor.size() == 1) {
            return byDescriptor.iterator().next();
        }

        return byName.size() == 1 ? byName.iterator().next() : null;
    }

    // --------------------------------------------------------------- accessors

    private boolean adaptAccessor(String className, MethodNode method, List<Target> targets,
            TransformContext context) {
        AnnotationNode accessor = annotation(allAnnotations(method), ACCESSOR);

        if (accessor != null) {
            return adaptAccessor(className, method, accessor, targets, context);
        }

        AnnotationNode invoker = annotation(allAnnotations(method), INVOKER);
        return invoker != null && adaptInvoker(className, method, invoker, targets, context);
    }

    private boolean adaptAccessor(String className, MethodNode method, AnnotationNode accessor,
            List<Target> targets, TransformContext context) {
        String wanted = declaredOrInferred(accessor, method.name);

        if (wanted == null) {
            return false;
        }

        // The field must be there with the type the accessor reads: 26.x's
        // RenderSystem keeps a shaderLightDirections that is a GPU buffer now,
        // not the Vector3f[] Catnip's accessor reads, and mixin refuses it.
        String descriptor = accessedFieldDescriptor(method);

        for (Target target : targets) {
            if (target.hasField(wanted, descriptor)) {
                return false;
            }
        }

        String resolved = null;

        for (Target target : targets) {
            for (String alias : table.fieldAliases(target.name(), wanted)) {
                if (target.hasField(alias, null)) {
                    resolved = alias;
                    break;
                }
            }

            if (resolved == null && descriptor != null) {
                resolved = target.onlyFieldOfType(descriptor);
            }

            if (resolved != null) {
                break;
            }
        }

        if (resolved == null) {
            retire(method, accessor);
            note(context, className, method.name + ": this version has no field " + wanted
                    + " nor one like it, so the accessor answers a default");
            return true;
        }

        put(accessor, "value", resolved);
        note(context, className, method.name + ": this version has no field " + wanted
                + ", so the accessor reads " + resolved + " instead");
        return true;
    }

    private boolean adaptInvoker(String className, MethodNode method, AnnotationNode invoker,
            List<Target> targets, TransformContext context) {
        String wanted = declaredOrInferred(invoker, method.name);

        if (wanted == null) {
            return false;
        }

        for (Target target : targets) {
            if (target.hasMethod(wanted, method.desc)) {
                return false;
            }
        }

        // There under the same name, its parameters narrowed or widened:
        // FlowingFluid.getNewLiquid takes a ServerLevel where Create's invoker
        // passes a Level. Mixin matches an invoker's descriptor exactly and
        // fails the whole accessor mixin, so the invoker calls the method
        // itself, by reflection, with the arguments it was given.
        if (wanted.charAt(0) != '<') {
            for (Target target : targets) {
                String other = target.onlyOverload(wanted, Type.getArgumentTypes(method.desc).length);

                if (other != null) {
                    UnmergedCalls.retireToCall(method, invoker, target.name(), wanted, other);
                    note(context, className, method.name + ": " + wanted + " takes " + other
                            + " now, so the invoker calls it with what it is given");
                    return true;
                }
            }
        }

        for (Target target : targets) {
            if (target.hasMethod(wanted, null)) {
                return false;
            }
        }

        for (Target target : targets) {
            String resolved = null;

            for (String alias : table.methodAliases(target.name(), wanted)) {
                if (target.hasMethod(alias, method.desc)) {
                    resolved = alias;
                    break;
                }
            }

            if (resolved == null) {
                resolved = target.onlyMethodOfType(method.desc);
            }

            if (resolved != null) {
                put(invoker, "value", resolved);
                note(context, className, method.name + ": this version has no method " + wanted
                        + ", so the invoker calls " + resolved + " instead");
                return true;
            }
        }

        retire(method, invoker);
        note(context, className, method.name + ": this version has no method " + wanted
                + " nor one like it, so the invoker answers a default");
        return true;
    }

    /**
     * An accessor or invoker for a member that is gone, with nothing like it
     * left to aim at, made an ordinary method that answers a default.
     *
     * <p>Left as it was, mixin fails to generate it, and every use of it calls
     * a method that was never added: Catnip's accessor for {@code
     * RenderSystem.shaderLightDirections}, which 26.x removed, was a {@code
     * NoSuchMethodError} in the middle of drawing every Create part. An
     * ordinary method stays in the accessor interface, which the target
     * still implements, and answers what a member the game no longer has
     * answers anywhere else.
     */
    private static void retire(MethodNode method, AnnotationNode annotation) {
        if (method.visibleAnnotations != null) {
            method.visibleAnnotations.remove(annotation);
        }

        if (method.invisibleAnnotations != null) {
            method.invisibleAnnotations.remove(annotation);
        }

        Type returned = Type.getReturnType(method.desc);
        org.objectweb.asm.tree.InsnList body = new org.objectweb.asm.tree.InsnList();

        switch (returned.getSort()) {
            case Type.VOID -> body.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                body.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_0));
                body.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.IRETURN));
            }
            case Type.LONG -> {
                body.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.LCONST_0));
                body.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                body.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.FCONST_0));
                body.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                body.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DCONST_0));
                body.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DRETURN));
            }
            default -> {
                body.add(new org.objectweb.asm.tree.LdcInsnNode(returned.getDescriptor()));
                body.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                        "studios/milkdromeda/octo/mixin/UnmergedCalls", "value",
                        "(Ljava/lang/String;)Ljava/lang/Object;", false));
                body.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST,
                        returned.getSort() == Type.ARRAY ? returned.getDescriptor() : returned.getInternalName()));
                body.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARETURN));
            }
        }

        // Synthetic, so mixin still counts the interface an accessor mixin:
        // one ordinary method in it and mixin takes the whole interface for
        // an interface mixin, which may only target an interface.
        boolean isStatic = (method.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
        method.access = (method.access & ~org.objectweb.asm.Opcodes.ACC_ABSTRACT) | org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
        method.instructions = body;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.maxStack = 2;
        method.maxLocals = (Type.getArgumentsAndReturnSizes(method.desc) >> 2) - (isStatic ? 1 : 0);
    }

    private static String declaredOrInferred(AnnotationNode annotation, String methodName) {
        if (value(annotation, "value") instanceof String declared && !declared.isEmpty()) {
            return declared;
        }

        return inferredName(methodName);
    }

    /** {@code getEntries} names the member {@code entries}; so does {@code setEntries}. */
    private static String inferredName(String accessorName) {
        for (String prefix : List.of("get", "set", "is", "invoke", "call")) {
            if (accessorName.length() > prefix.length() && accessorName.startsWith(prefix)) {
                String rest = accessorName.substring(prefix.length());

                // As mixin names it: setGRID_WIDTH reads GRID_WIDTH, not gRID_WIDTH.
                if (rest.toUpperCase(java.util.Locale.ROOT).equals(rest)) {
                    return rest;
                }

                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }

        return null;
    }

    /** A getter's return type or a setter's only parameter — the field's own type either way. */
    private static String accessedFieldDescriptor(MethodNode accessor) {
        Type type = Type.getMethodType(accessor.desc);

        if (type.getReturnType().getSort() != Type.VOID) {
            return type.getReturnType().getDescriptor();
        }

        return type.getArgumentTypes().length == 1 ? type.getArgumentTypes()[0].getDescriptor() : null;
    }

    // ----------------------------------------------------------------- shadows

    private boolean adaptShadowField(ClassNode mixin, String className, FieldNode field, List<Target> targets,
            TransformContext context) {
        AnnotationNode shadow = annotation(allAnnotations(field), SHADOW);

        if (shadow == null) {
            return false;
        }

        String wanted = shadowedName(shadow, field.name);

        for (Target target : targets) {
            if (target.hasField(wanted, null)) {
                return false;
            }
        }

        List<String> candidates = new ArrayList<>();

        for (Target target : targets) {
            candidates.addAll(table.fieldAliases(target.name(), wanted));
            String only = target.onlyFieldOfType(field.desc);

            if (only != null) {
                candidates.add(only);
            }
        }

        if (candidates.isEmpty()) {
            return addField(mixin, className, field, wanted, context);
        }

        return addAliases(shadow, candidates, context, className,
                "shadowed field " + wanted + " is not in this version");
    }

    /**
     * Turns a shadow of a field the target does not have into a field the
     * mixin adds to it.
     *
     * <p>A {@code @Shadow} mixin cannot locate is an {@code InvalidMixinException}
     * for the whole mixin. The fields that go missing are mostly ones a loader's
     * patches gave the game — Forge's {@code confirmedExperimentalWarning} on
     * {@code PrimaryLevelData} — and the mixin's own code is what reads and
     * writes them. Added to the target as an ordinary field, it is a field that
     * code can use, and the rest of the mixin applies.
     */
    private boolean addField(ClassNode mixin, String className, FieldNode field, String wanted,
            TransformContext context) {
        if (field.visibleAnnotations != null) {
            field.visibleAnnotations.removeIf(annotation -> SHADOW_COMPANIONS.contains(annotation.desc));
        }

        if (field.invisibleAnnotations != null) {
            field.invisibleAnnotations.removeIf(annotation -> SHADOW_COMPANIONS.contains(annotation.desc));
        }

        String initial = initialiseFromTypeDefault(mixin, field);
        note(context, className, "shadowed field " + wanted + " is not in this version, so the mixin adds it"
                + (initial == null ? "" : ", starting as " + initial));
        return true;
    }

    /** The constants a loader's own types give their "nothing particular" value, most telling first. */
    private static final List<String> TYPE_DEFAULTS = List.of("DEFAULT", "UNSPECIFIED", "EMPTY", "NONE");

    /**
     * Starts a field the mixin now adds at the value its type gives for
     * nothing particular, when the type has one.
     *
     * <p>Such a field was given to the game by the loader's patch, which also
     * filled it in: NeoForge's quads carry {@code bakedColors}, which a quad
     * built without colours of its own holds as {@code BakedColors.DEFAULT}.
     * Added by the mixin it started as null instead, and Sodium, reading
     * every quad's colours through it, ended the game on the first chunk.
     * Mixin copies what a mixin's constructor sets after its super call into
     * each of the target's constructors, so that is where the value is set.
     *
     * @return the constant the field starts as, or {@code null} when it starts empty
     */
    private String initialiseFromTypeDefault(ClassNode mixin, FieldNode field) {
        if ((field.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0 || field.desc.charAt(0) != 'L') {
            return null;
        }

        String type = Type.getType(field.desc).getInternalName();
        String chosen = typeDefault(Type.getType(field.desc));

        if (chosen == null) {
            return null;
        }

        boolean set = false;

        for (MethodNode constructor : mixin.methods) {
            if (!constructor.name.equals("<init>") || constructor.instructions == null) {
                continue;
            }

            // Mixin takes as initialisers the code after the super call that
            // lies on lines outside the constructor's own, so the value is set
            // on a line past its last and the line it was on resumes after.
            int lastLine = -1;
            int superLine = -1;
            int line = -1;
            org.objectweb.asm.tree.MethodInsnNode superCall = null;

            for (org.objectweb.asm.tree.AbstractInsnNode instruction : constructor.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.LineNumberNode number) {
                    line = number.line;
                    lastLine = Math.max(lastLine, line);
                } else if (superCall == null && instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.getOpcode() == org.objectweb.asm.Opcodes.INVOKESPECIAL && call.name.equals("<init>")
                        && call.owner.equals(mixin.superName)) {
                    superCall = call;
                    superLine = line;
                }
            }

            if (superCall == null || superLine < 0) {
                continue;
            }

            org.objectweb.asm.tree.InsnList start = new org.objectweb.asm.tree.InsnList();
            org.objectweb.asm.tree.LabelNode own = new org.objectweb.asm.tree.LabelNode();
            start.add(own);
            start.add(new org.objectweb.asm.tree.LineNumberNode(lastLine + 1, own));
            start.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
            start.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC, type, chosen,
                    field.desc));
            start.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTFIELD, mixin.name,
                    field.name, field.desc));
            org.objectweb.asm.tree.LabelNode resume = new org.objectweb.asm.tree.LabelNode();
            start.add(resume);
            start.add(new org.objectweb.asm.tree.LineNumberNode(superLine, resume));
            constructor.instructions.insert(superCall, start);
            constructor.maxStack = Math.max(constructor.maxStack, 2);
            set = true;
        }

        if (!set) {
            return null;
        }

        field.access &= ~org.objectweb.asm.Opcodes.ACC_FINAL;
        return type.substring(type.lastIndexOf('/') + 1) + "." + chosen;
    }

    private boolean adaptShadowMethod(String className, MethodNode method, List<Target> targets,
            TransformContext context) {
        AnnotationNode shadow = annotation(allAnnotations(method), SHADOW);

        if (shadow == null) {
            return false;
        }

        String wanted = shadowedName(shadow, method.name);

        for (Target target : targets) {
            if (target.hasMethod(wanted, method.desc)) {
                return false;
            }
        }

        List<String> candidates = new ArrayList<>();

        for (Target target : targets) {
            candidates.addAll(table.methodAliases(target.name(), wanted));
        }

        return addAliases(shadow, candidates, context, className,
                "shadowed method " + wanted + " is not in this version");
    }

    /**
     * Hands mixin the other names to try, rather than choosing one here.
     *
     * <p>{@code @Shadow} already carries an {@code aliases} field for exactly
     * this situation, and letting mixin pick keeps the decision where the rest of
     * a shadow's rules live.
     */
    private boolean addAliases(AnnotationNode shadow, List<String> candidates, TransformContext context,
            String className, String description) {
        if (candidates.isEmpty()) {
            return false;
        }

        Set<String> aliases = new LinkedHashSet<>(strings(value(shadow, "aliases")));

        if (!aliases.addAll(candidates)) {
            return false;
        }

        put(shadow, "aliases", new ArrayList<>(aliases));
        note(context, className, description + ", so " + String.join(", ", candidates) + " is offered instead");
        return true;
    }

    /** {@code @Shadow(prefix = "octo$")} on {@code octo$tick} shadows {@code tick}. */
    private static String shadowedName(AnnotationNode shadow, String declaredName) {
        if (value(shadow, "prefix") instanceof String prefix && !prefix.isEmpty()
                && declaredName.startsWith(prefix)) {
            return declaredName.substring(prefix.length());
        }

        return declaredName;
    }

    // ------------------------------------------------------------- target shape

    private record Member(String name, String descriptor) {
    }

    /** A target class flattened over its superclasses. */
    private record Target(String name, List<Member> methods, List<Member> fields) {
        boolean hasMethod(String memberName, String descriptor) {
            return has(methods, memberName, descriptor);
        }

        boolean hasField(String memberName, String descriptor) {
            return has(fields, memberName, descriptor);
        }

        private static boolean has(List<Member> members, String memberName, String descriptor) {
            for (Member member : members) {
                if (member.name().equals(memberName)
                        && (descriptor == null || member.descriptor().equals(descriptor))) {
                    return true;
                }
            }

            return false;
        }

        int countMethods(String memberName) {
            int count = 0;

            for (Member method : methods) {
                if (method.name().equals(memberName)) {
                    count++;
                }
            }

            return count;
        }

        /**
         * @return the name of the only field of that type, or {@code null} when
         *         there is none or more than one
         *
         * <p>This is what answers a report of "no candidates were found matching
         * entries:Ljava/util/Map;". A class with exactly one field of a given
         * type leaves no room for a wrong guess, and the type is the part of an
         * accessor that does not drift.
         */
        String onlyFieldOfType(String descriptor) {
            return only(fields, descriptor, false);
        }

        /** @return the descriptor of the only method of that name taking that many arguments, or {@code null} */
        String onlyOverload(String memberName, int arity) {
            String found = null;

            for (Member method : methods) {
                if (method.name().equals(memberName)
                        && Type.getArgumentTypes(method.descriptor()).length == arity) {
                    if (found != null && !found.equals(method.descriptor())) {
                        return null;
                    }

                    found = method.descriptor();
                }
            }

            return found;
        }

        /** @return the name of the only method of that shape, or {@code null} */
        String onlyMethodOfType(String descriptor) {
            return only(methods, descriptor, true);
        }

        private static String only(List<Member> members, String descriptor, boolean skipInitialisers) {
            String found = null;

            for (Member member : members) {
                if (!member.descriptor().equals(descriptor)
                        || (skipInitialisers && member.name().charAt(0) == '<')) {
                    continue;
                }

                if (found != null && !found.equals(member.name())) {
                    return null;
                }

                found = member.name();
            }

            return found;
        }
    }

    // ------------------------------------------------------------- annotations

    private static List<AnnotationNode> allAnnotations(MethodNode method) {
        return join(method.visibleAnnotations, method.invisibleAnnotations);
    }

    private static List<AnnotationNode> allAnnotations(FieldNode field) {
        return join(field.visibleAnnotations, field.invisibleAnnotations);
    }

    private static List<AnnotationNode> join(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> out = new ArrayList<>();

        if (visible != null) {
            out.addAll(visible);
        }

        if (invisible != null) {
            out.addAll(invisible);
        }

        return out;
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return null;
        }

        for (AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) {
                return annotation;
            }
        }

        return null;
    }

    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return null;
        }

        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }

        return null;
    }

    private static void put(AnnotationNode annotation, String name, Object value) {
        if (annotation.values == null) {
            annotation.values = new ArrayList<>();
        }

        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                annotation.values.set(i + 1, value);
                return;
            }
        }

        annotation.values.add(name);
        annotation.values.add(value);
    }

    /** An annotation value that is either one string or an array of them. */
    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();

        if (value instanceof String single) {
            out.add(single);
        } else if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof String text) {
                    out.add(text);
                }
            }
        }

        return out;
    }

    private void note(TransformContext context, String className, String description) {
        String entry = className.replace('/', '.') + ": " + description;

        if (adjustments.add(entry)) {
            LOG.info("{}", entry);
        }

        context.note(description);
    }

    /**
     * One target as a mixin writes it: {@code tick}, {@code tick()V}, or
     * {@code Lnet/minecraft/client/Minecraft;tick()V}.
     */
    private record MemberRef(String original, String owner, String name, String descriptor) {
        /** @return the parsed reference, or {@code null} for a form best left to mixin */
        static MemberRef parse(String spec) {
            String text = spec.trim();

            // A wildcard, a @Desc reference or a namespaced selector: mixin's own
            // resolver understands these and this one does not, so it stands back.
            // A regular-expression selector, /^with/ desc=/...$/, is mixin's too.
            if (text.isEmpty() || text.indexOf('*') >= 0 || text.indexOf('{') >= 0
                    || text.charAt(0) == '@' || text.charAt(0) == '/' || text.indexOf(':') >= 0) {
                return null;
            }

            String owner = null;
            String rest = text;

            if (rest.charAt(0) == 'L') {
                int end = rest.indexOf(';');
                int open = rest.indexOf('(');

                if (end > 0 && (open < 0 || end < open)) {
                    owner = rest.substring(1, end);
                    rest = rest.substring(end + 1);
                }
            }

            int open = rest.indexOf('(');
            String name = open < 0 ? rest : rest.substring(0, open);
            String descriptor = open < 0 ? null : rest.substring(open);
            return name.isEmpty() ? null : new MemberRef(text, owner, name, descriptor);
        }

        /** The same reference with a different member name, keeping owner and descriptor. */
        String rename(String replacement) {
            StringBuilder out = new StringBuilder();

            if (owner != null) {
                out.append('L').append(owner).append(';');
            }

            out.append(replacement);

            if (descriptor != null) {
                out.append(descriptor);
            }

            return out.toString();
        }
    }
}
