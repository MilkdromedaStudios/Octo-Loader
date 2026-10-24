package studios.milkdromeda.octo.rebuild;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Translates the member names a mixin carries as text.
 *
 * <p>Fabric mods ship a refmap that says what every name in their mixin
 * annotations means, and the rebuild translates the refmap. NeoForge and
 * Forge mods since 1.20.5 have no refmap: their mixins name the game's members
 * in Mojang's names, as plain strings, and a rename between the release they
 * were built for and this one leaves those strings pointing at nothing. Jade's
 * accessor for the furnace's {@code cookingProgress} — {@code cookingTimer}
 * since 1.21.2 — is a mixin that no longer applies.
 *
 * <p>So the strings get the same translation the class's bytecode does:
 * injector {@code method} targets and {@code @At} targets against the mixin's
 * own targets, and {@code @Accessor} and {@code @Invoker} names — written out,
 * or inferred from the method's own name the way mixin infers them. Shadows
 * need nothing here; the class remap reaches them through the mixin's targets.
 */
final class MixinStrings {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String MIXIN_PACKAGE = "org/spongepowered/asm/mixin/";

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

    private MixinStrings() {
    }

    /**
     * @param remapper the rebuild's remapper, which takes names in the mod's
     *                 namespace
     * @return the class with its mixin strings translated, or the same array
     *         when it is not a mixin or nothing changed
     */
    static byte[] translate(byte[] bytes, Remapper remapper, List<String> targets) {
        if (targets.isEmpty() || !mentions(bytes, MIXIN_PACKAGE)) {
            return bytes;
        }

        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;

        for (MethodNode method : node.methods) {
            for (AnnotationNode annotation : annotations(method)) {
                if (INJECTORS.contains(annotation.desc)) {
                    changed |= translateInjector(annotation, remapper, targets);
                } else if (ACCESSOR.equals(annotation.desc)) {
                    changed |= translateAccessor(annotation, method, remapper, targets);
                } else if (INVOKER.equals(annotation.desc)) {
                    changed |= translateInvoker(annotation, method, remapper, targets);
                }
            }
        }

        if (!changed) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    // ---------------------------------------------------------------- injectors

    private static boolean translateInjector(AnnotationNode annotation, Remapper remapper, List<String> targets) {
        boolean changed = false;

        if (annotation.values == null) {
            return false;
        }

        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            Object key = annotation.values.get(i);
            Object value = annotation.values.get(i + 1);

            if ("method".equals(key) && value instanceof List<?> list) {
                List<Object> translated = new ArrayList<>(list.size());

                for (Object item : list) {
                    String mapped = item instanceof String text ? methodReference(text, remapper, targets) : null;
                    translated.add(mapped != null ? mapped : item);
                    changed |= mapped != null;
                }

                annotation.values.set(i + 1, translated);
            } else {
                changed |= translateNested(value, remapper);
            }
        }

        return changed;
    }

    /** {@code at = @At(...)}, {@code slice = @Slice(from = @At(...))} and lists of either. */
    private static boolean translateNested(Object value, Remapper remapper) {
        boolean changed = false;

        if (value instanceof List<?> list) {
            for (Object item : list) {
                changed |= translateNested(item, remapper);
            }
        } else if (value instanceof AnnotationNode nested && nested.values != null) {
            for (int i = 0; i + 1 < nested.values.size(); i += 2) {
                Object key = nested.values.get(i);
                Object inner = nested.values.get(i + 1);

                if (AT.equals(nested.desc) && "target".equals(key) && inner instanceof String text) {
                    String mapped = qualifiedReference(text, remapper);

                    if (mapped != null) {
                        nested.values.set(i + 1, mapped);
                        changed = true;
                    }
                } else {
                    changed |= translateNested(inner, remapper);
                }
            }
        }

        return changed;
    }

    /**
     * An injector's {@code method}: {@code tick}, {@code tick()V} or
     * {@code Lowner;tick()V}, against the mixin's targets.
     *
     * @return the translation, or {@code null} when it does not change
     */
    static String methodReference(String reference, Remapper remapper, List<String> targets) {
        if (reference.isEmpty() || reference.indexOf('*') >= 0 || reference.startsWith("/")
                || reference.charAt(0) == '<' || reference.contains(":")) {
            return null;
        }

        if (reference.charAt(0) == 'L' && reference.indexOf(';') > 0) {
            return qualifiedReference(reference, remapper);
        }

        int open = reference.indexOf('(');
        String name = open < 0 ? reference : reference.substring(0, open);
        String descriptor = open < 0 ? null : reference.substring(open);

        if (name.startsWith("<")) {
            return null;
        }

        for (String target : targets) {
            String mapped = remapper.mapMethodName(target, name, descriptor);

            if (!mapped.equals(name)) {
                return mapped + (descriptor == null ? "" : remapper.mapMethodDesc(descriptor));
            }
        }

        return null;
    }

    /**
     * A fully qualified member: {@code Lowner;name(desc)ret} for a method,
     * {@code Lowner;name:desc} for a field. Class names in it are left to the
     * class remap; what this adds is the member's own name.
     */
    static String qualifiedReference(String reference, Remapper remapper) {
        int semicolon = reference.indexOf(';');

        if (reference.isEmpty() || reference.charAt(0) != 'L' || semicolon < 0 || semicolon == reference.length() - 1) {
            return null;
        }

        String owner = reference.substring(1, semicolon);
        String rest = reference.substring(semicolon + 1);
        int open = rest.indexOf('(');
        int colon = rest.indexOf(':');
        String mapped;

        if (open > 0) {
            String name = rest.substring(0, open);
            String descriptor = rest.substring(open);

            if (name.startsWith("<")) {
                return null;
            }

            mapped = remapper.mapMethodName(owner, name, descriptor);

            if (mapped.equals(name)) {
                return null;
            }

            return "L" + owner + ";" + mapped + descriptor;
        }

        if (colon > 0) {
            String name = rest.substring(0, colon);
            String descriptor = rest.substring(colon + 1);
            mapped = remapper.mapFieldName(owner, name, descriptor);
            return mapped.equals(name) ? null : "L" + owner + ";" + mapped + ":" + descriptor;
        }

        return null;
    }

    // ---------------------------------------------------- accessors and invokers

    private static boolean translateAccessor(AnnotationNode annotation, MethodNode method, Remapper remapper,
            List<String> targets) {
        String declared = value(annotation);
        String name = declared != null && !declared.isEmpty() ? declared : inferredField(method.name);

        if (name == null) {
            return false;
        }

        Type type = Type.getMethodType(method.desc);
        String descriptor = type.getReturnType().getSort() == Type.VOID && type.getArgumentTypes().length > 0
                ? type.getArgumentTypes()[type.getArgumentTypes().length - 1].getDescriptor()
                : type.getReturnType().getDescriptor();

        for (String target : targets) {
            String mapped = remapper.mapFieldName(target, name, descriptor);

            if (!mapped.equals(name)) {
                setValue(annotation, mapped);
                return true;
            }
        }

        return false;
    }

    private static boolean translateInvoker(AnnotationNode annotation, MethodNode method, Remapper remapper,
            List<String> targets) {
        String declared = value(annotation);
        String name = declared != null && !declared.isEmpty() ? declared : inferredMethod(method.name);

        if (name == null || name.startsWith("<")) {
            return false;
        }

        for (String target : targets) {
            String mapped = remapper.mapMethodName(target, name, method.desc);

            if (!mapped.equals(name)) {
                setValue(annotation, mapped);
                return true;
            }
        }

        return false;
    }

    /** Mixin's own rule: {@code getCookingProgress} accesses {@code cookingProgress}. */
    static String inferredField(String accessor) {
        for (String prefix : List.of("get", "set", "is")) {
            if (accessor.startsWith(prefix) && accessor.length() > prefix.length()) {
                return decapitalise(accessor.substring(prefix.length()));
            }
        }

        return null;
    }

    /** {@code callTick} and {@code invokeTick} invoke {@code tick}. */
    static String inferredMethod(String invoker) {
        for (String prefix : List.of("call", "invoke")) {
            if (invoker.startsWith(prefix) && invoker.length() > prefix.length()) {
                return decapitalise(invoker.substring(prefix.length()));
            }
        }

        return null;
    }

    /** {@code CookingProgress} to {@code cookingProgress}; {@code MAX_VALUE} stays as it is. */
    private static String decapitalise(String name) {
        if (name.equals(name.toUpperCase(Locale.ROOT))) {
            return name;
        }

        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    // ------------------------------------------------------------------ helpers

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> out = new ArrayList<>();

        if (method.visibleAnnotations != null) {
            out.addAll(method.visibleAnnotations);
        }

        if (method.invisibleAnnotations != null) {
            out.addAll(method.invisibleAnnotations);
        }

        return out;
    }

    private static String value(AnnotationNode annotation) {
        if (annotation.values == null) {
            return null;
        }

        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if ("value".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String text) {
                return text;
            }
        }

        return null;
    }

    private static void setValue(AnnotationNode annotation, String value) {
        if (annotation.values == null) {
            annotation.values = new ArrayList<>();
        }

        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if ("value".equals(annotation.values.get(i))) {
                annotation.values.set(i + 1, value);
                return;
            }
        }

        annotation.values.add("value");
        annotation.values.add(value);
    }

    private static boolean mentions(byte[] bytes, String text) {
        byte[] needle = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }

            return true;
        }

        return false;
    }
}
