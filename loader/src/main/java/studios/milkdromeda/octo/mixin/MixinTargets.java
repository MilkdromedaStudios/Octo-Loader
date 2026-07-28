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
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");

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
            // Either the mixin has no resolvable target — an abstract base that
            // real mixins extend — or the target is not on the class path, and
            // mixin's own message about that is the useful one.
            return bytes;
        }

        boolean changed = false;

        for (MethodNode method : mixin.methods) {
            changed |= adaptInjectors(className, method, targets, context);
            changed |= adaptAccessor(className, method, targets, context);
            changed |= adaptShadowMethod(className, method, targets, context);
        }

        for (FieldNode field : mixin.fields) {
            changed |= adaptShadowField(className, field, targets, context);
        }

        if (!changed) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(0);
        mixin.accept(writer);
        return writer.toByteArray();
    }

    // ------------------------------------------------------------------ targets

    private List<Target> readTargets(AnnotationNode declaration) {
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

        List<Target> out = new ArrayList<>();

        for (String name : names) {
            Target target = shapeOf(name);

            if (target != null) {
                out.add(target);
            }
        }

        return out;
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

    private boolean adaptInjectors(String className, MethodNode method, List<Target> targets,
            TransformContext context) {
        boolean changed = false;

        for (AnnotationNode annotation : allAnnotations(method)) {
            if (INJECTORS.contains(annotation.desc)) {
                changed |= adaptInjector(className, method, annotation, targets, context);
            }
        }

        return changed;
    }

    private boolean adaptInjector(String className, MethodNode method, AnnotationNode annotation,
            List<Target> targets, TransformContext context) {
        List<String> specs = strings(value(annotation, "method"));

        if (specs.isEmpty()) {
            // Either a @Desc-based target, which mixin resolves structurally and
            // does not need help with, or a form this cannot read. Left alone.
            return false;
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

        for (Target target : targets) {
            if (target.hasField(wanted, null)) {
                return false;
            }
        }

        String descriptor = accessedFieldDescriptor(method);
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
            return false;
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

        return false;
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

    private boolean adaptShadowField(String className, FieldNode field, List<Target> targets,
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

        return addAliases(shadow, candidates, context, className,
                "shadowed field " + wanted + " is not in this version");
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
            if (text.isEmpty() || text.indexOf('*') >= 0 || text.indexOf('{') >= 0
                    || text.charAt(0) == '@' || text.indexOf(':') >= 0) {
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
