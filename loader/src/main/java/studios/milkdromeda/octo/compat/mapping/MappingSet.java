package studios.milkdromeda.octo.compat.mapping;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.objectweb.asm.commons.Remapper;

/**
 * A rename table: old name to new name, for classes, methods and fields.
 *
 * <p>Sets compose. Getting a 1.7.10 mod onto a modern runtime is not one
 * rename but a chain of them — MCP names to obfuscated names to intermediary
 * names to today's names — and {@link #then(MappingSet)} builds that chain so
 * the bytecode only has to be rewritten once.
 *
 * <p>Members are looked up three ways, most specific first: by owner, name and
 * descriptor; by owner and name where that is unambiguous; and by name alone
 * for the namespaces whose member names are unique identifiers on their own —
 * Fabric's {@code method_1551}, Forge's {@code m_91087_}. The last is what makes
 * a call through a mod's own subclass translate: {@code invokevirtual
 * MyEntity.method_5773} names an owner no table has ever heard of, and the name
 * alone is enough.
 */
public final class MappingSet {
    /** Identity mapping; composing with it changes nothing. */
    public static final MappingSet EMPTY = new MappingSet("identity", Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of());

    /** Member names that identify a member by themselves, whatever class it is on. */
    private static final Pattern UNIQUE_MEMBER = Pattern.compile(
            "(?:method|field|comp)_\\d+|[mf]_\\d+_|(?:func|field)_\\d+_[a-zA-Z0-9]+_?");

    /** Tokens in free text — refmaps, reflection strings — that a table may know. */
    private static final Pattern TOKEN = Pattern.compile(
            "L[A-Za-z0-9_/$]+;|net[/.]minecraft[/.]class_\\d+(?:\\$class_\\d+)*|(?:method|field|comp)_\\d+"
                    + "|[mf]_\\d+_|(?:func|field)_\\d+_[a-zA-Z0-9]+_?");

    private final String name;
    private final Map<String, String> classes;
    private final Map<MemberKey, String> methods;
    private final Map<MemberKey, String> fields;
    /** owner + name to target, for the many tables whose descriptors do not line up. */
    private final Map<String, String> methodsByName;
    private final Map<String, String> fieldsByName;
    /** name alone to target, for unique-identifier namespaces and unambiguous renames. */
    private final Map<String, String> globalMethods;
    private final Map<String, String> globalFields;
    private volatile Map<String, String> inverseClasses;
    private final Remapper remapper;

    private MappingSet(String name, Map<String, String> classes, Map<MemberKey, String> methods,
            Map<MemberKey, String> fields, Map<String, String> globalMethods, Map<String, String> globalFields) {
        this.name = name;
        this.classes = Map.copyOf(classes);
        this.methods = Map.copyOf(methods);
        this.fields = Map.copyOf(fields);
        this.methodsByName = indexByName(methods);
        this.fieldsByName = indexByName(fields);
        this.globalMethods = Map.copyOf(globalMethods);
        this.globalFields = Map.copyOf(globalFields);
        this.remapper = new SetRemapper(null);
    }

    /**
     * Builds the descriptor-blind index.
     *
     * <p>Chained mapping tables rarely agree on descriptors: each hop writes them
     * in its own namespace, so a lookup with the descriptor from one hop misses
     * in the next. Where a name is unambiguous within its class, matching on the
     * name alone is correct and recovers the mapping. Where it is not — an
     * overloaded method with different targets per signature — the entry is
     * dropped, and only an exact descriptor match will do.
     */
    private static Map<String, String> indexByName(Map<MemberKey, String> members) {
        Map<String, String> index = new HashMap<>();

        for (Map.Entry<MemberKey, String> entry : members.entrySet()) {
            String key = entry.getKey().owner() + "#" + entry.getKey().name();
            String existing = index.putIfAbsent(key, entry.getValue());

            if (existing != null && !existing.equals(entry.getValue())) {
                index.put(key, AMBIGUOUS);
            }
        }

        return index;
    }

    private static final String AMBIGUOUS = "\u0000ambiguous";

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public boolean isEmpty() {
        return classes.isEmpty() && methods.isEmpty() && fields.isEmpty() && globalMethods.isEmpty()
                && globalFields.isEmpty();
    }

    public int size() {
        return classes.size() + methods.size() + fields.size() + globalMethods.size() + globalFields.size();
    }

    public Map<String, String> classes() {
        return classes;
    }

    /** The ASM remapper that applies this set. */
    public Remapper remapper() {
        return remapper;
    }

    /**
     * A remapper that, when a member is not found on the owner a reference
     * names, asks the owner's parents.
     *
     * <p>Tables list a member on the class that declares it, and code calls it
     * through whatever class it has in hand — very often the mod's own subclass.
     *
     * @param parents the superclass and interfaces of a class, in the source
     *                namespace, or an empty list when unknown
     */
    public Remapper remapper(Function<String, List<String>> parents) {
        return new SetRemapper(parents);
    }

    public String mapClass(String internalName) {
        String mapped = classes.get(internalName);

        if (mapped != null) {
            return mapped;
        }

        // An inner class the table has no entry for still moves with its outer one.
        int dollar = internalName.lastIndexOf('$');

        if (dollar > 0) {
            String outer = classes.get(internalName.substring(0, dollar));

            if (outer != null) {
                return outer + internalName.substring(dollar);
            }
        }

        return internalName;
    }

    public String mapMethod(String owner, String name, String descriptor) {
        String mapped = lookupMethod(owner, name, descriptor);
        return mapped != null ? mapped : name;
    }

    private String lookupMethod(String owner, String name, String descriptor) {
        String mapped = methods.get(new MemberKey(owner, name, descriptor));

        if (mapped != null) {
            return mapped;
        }

        mapped = methods.get(new MemberKey(owner, name, null));

        if (mapped != null) {
            return mapped;
        }

        mapped = methodsByName.get(owner + "#" + name);

        if (mapped != null && !mapped.equals(AMBIGUOUS)) {
            return mapped;
        }

        return globalMethods.get(name);
    }

    public String mapField(String owner, String name, String descriptor) {
        String mapped = lookupField(owner, name, descriptor);
        return mapped != null ? mapped : name;
    }

    private String lookupField(String owner, String name, String descriptor) {
        String mapped = fields.get(new MemberKey(owner, name, descriptor));

        if (mapped != null) {
            return mapped;
        }

        mapped = fields.get(new MemberKey(owner, name, null));

        if (mapped != null) {
            return mapped;
        }

        mapped = fieldsByName.get(owner + "#" + name);

        if (mapped != null && !mapped.equals(AMBIGUOUS)) {
            return mapped;
        }

        return globalFields.get(name);
    }

    /**
     * Translates the names a table knows wherever they appear in free text.
     *
     * <p>Mixin refmaps, reflection helpers and annotation strings carry class and
     * member names as text — {@code "Lnet/minecraft/class_310;method_1551()V"} —
     * where no bytecode remapper looks. The namespaces whose names are unique
     * identifiers can be translated token by token, and those are exactly the
     * ones that end up in such strings.
     */
    public String mapString(String text) {
        if (text == null || text.isEmpty() || isEmpty()) {
            return text;
        }

        Matcher matcher = TOKEN.matcher(text);

        if (!matcher.find()) {
            return text;
        }

        StringBuilder out = new StringBuilder(text.length());
        matcher.reset();

        while (matcher.find()) {
            String token = matcher.group();
            String replacement;

            if (token.charAt(0) == 'L' && token.endsWith(";")) {
                // A class inside a descriptor, as refmaps write them; its name
                // may itself contain numbered member-like text, so it is mapped
                // as a class and nothing else.
                replacement = "L" + mapClass(token.substring(1, token.length() - 1)) + ";";
            } else if (token.startsWith("net")) {
                boolean dotted = token.indexOf('.') >= 0;
                String internal = dotted ? token.replace('.', '/') : token;
                String mapped = mapClass(internal);
                replacement = dotted ? mapped.replace('/', '.') : mapped;
            } else {
                String method = globalMethods.get(token);
                replacement = method != null ? method : globalFields.getOrDefault(token, token);
            }

            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(out);
        return out.toString();
    }

    /** Whether a member name identifies its member without an owner. */
    public static boolean isUniqueMemberName(String name) {
        return UNIQUE_MEMBER.matcher(name).matches();
    }

    /** Reverses the direction of this set. */
    public MappingSet invert() {
        Builder builder = builder(name + "^-1");
        classes.forEach((from, to) -> builder.mapClass(to, from));

        methods.forEach((key, to) -> {
            String owner = mapClass(key.owner());
            String descriptor = key.descriptor() == null ? null : remapper.mapMethodDesc(key.descriptor());
            builder.mapMethod(owner, to, descriptor, key.name());
        });

        fields.forEach((key, to) -> {
            String owner = mapClass(key.owner());
            String descriptor = key.descriptor() == null ? null : remapper.mapDesc(key.descriptor());
            builder.mapField(owner, to, descriptor, key.name());
        });

        globalMethods.forEach((from, to) -> builder.mapMethodGlobally(to, from));
        globalFields.forEach((from, to) -> builder.mapFieldGlobally(to, from));
        return builder.build();
    }

    /**
     * This set, with {@code fallback} answering whatever this one does not know.
     *
     * <p>How a mod built for 1.21.1 reaches today's names: the newest table is
     * asked first, because it carries the names the running game uses, and the
     * mod's own version answers for anything removed since.
     */
    public MappingSet orElse(MappingSet fallback) {
        if (fallback == null || fallback.isEmpty()) {
            return this;
        }

        if (isEmpty()) {
            return fallback;
        }

        Builder builder = builder(name + " | " + fallback.name);
        fallback.classes.forEach(builder::mapClass);
        classes.forEach(builder::mapClass);
        builder.methods.putAll(fallback.methods);
        builder.methods.putAll(methods);
        builder.fields.putAll(fallback.fields);
        builder.fields.putAll(fields);
        builder.globalMethods.putAll(fallback.globalMethods);
        builder.globalMethods.putAll(globalMethods);
        builder.globalFields.putAll(fallback.globalFields);
        builder.globalFields.putAll(globalFields);
        return builder.build();
    }

    /**
     * This set, less what it would get wrong applied after {@code first}.
     *
     * <p>For a table keyed in the same names as {@code first} but applied to
     * what {@code first} produced: the renames found by comparing a mod's own
     * release with the running game, applied after the published tables have
     * taken the mod to 1.21.11's names. Its use is the members those tables
     * lost track of, which they leave as they were. A member they rename has
     * a new name by the time this is asked, and that name can be another old
     * member's: 1.21.1's {@code Registry.getHolder(key)} is {@code get(key)}
     * after the tables, and 1.21.1's own {@code get(key)} became {@code
     * getValue}, so the second step turned every {@code getHolder} into a
     * {@code getValue} that hands back the entry rather than its holder. So
     * an entry is kept only for a member {@code first} leaves alone, and only
     * where no member {@code first} renames, on any class, lands on its name.
     */
    public MappingSet unchangedBy(MappingSet first) {
        if (isEmpty() || first == null || first.isEmpty()) {
            return this;
        }

        // By name alone: a rename on Registry reaches every registry type a
        // call can name it through, and this has no hierarchy to follow.
        java.util.Set<String> methodTargets = new java.util.HashSet<>(first.globalMethods.values());
        first.methods.forEach((key, to) -> {
            if (!to.equals(key.name())) {
                methodTargets.add(to);
            }
        });
        java.util.Set<String> fieldTargets = new java.util.HashSet<>(first.globalFields.values());
        first.fields.forEach((key, to) -> {
            if (!to.equals(key.name())) {
                fieldTargets.add(to);
            }
        });

        Builder builder = builder(name + " beyond " + first.name);
        classes.forEach((from, to) -> {
            if (first.mapClass(from).equals(from)) {
                builder.mapClass(from, to);
            }
        });
        methods.forEach((key, to) -> {
            String renamed = first.lookupMethod(key.owner(), key.name(), key.descriptor());

            if ((renamed == null || renamed.equals(key.name())) && !methodTargets.contains(key.name())) {
                builder.mapMethod(key.owner(), key.name(), key.descriptor(), to);
            }
        });
        fields.forEach((key, to) -> {
            String renamed = first.lookupField(key.owner(), key.name(), key.descriptor());

            if ((renamed == null || renamed.equals(key.name())) && !fieldTargets.contains(key.name())) {
                builder.mapField(key.owner(), key.name(), key.descriptor(), to);
            }
        });
        globalMethods.forEach((from, to) -> {
            if (!first.globalMethods.containsKey(from) && !methodTargets.contains(from)) {
                builder.mapMethodGlobally(from, to);
            }
        });
        globalFields.forEach((from, to) -> {
            if (!first.globalFields.containsKey(from) && !fieldTargets.contains(from)) {
                builder.mapFieldGlobally(from, to);
            }
        });
        return builder.build();
    }

    /**
     * Composes with a second set, so that applying the result once is the same as
     * applying this and then {@code next}.
     */
    public MappingSet then(MappingSet next) {
        if (isEmpty()) {
            return next;
        }

        if (next.isEmpty()) {
            return this;
        }

        Builder builder = builder(name + " -> " + next.name);

        // Classes reachable from either hop.
        Map<String, String> composedClasses = new LinkedHashMap<>();
        classes.forEach((from, to) -> composedClasses.put(from, next.mapClass(to)));
        next.classes.forEach(composedClasses::putIfAbsent);
        composedClasses.forEach(builder::mapClass);

        methods.forEach((key, to) -> {
            String owner = mapClass(key.owner());
            String descriptor = key.descriptor() == null ? null : remapper.mapMethodDesc(key.descriptor());
            builder.mapMethod(key.owner(), key.name(), key.descriptor(), next.mapMethod(owner, to, descriptor));
        });

        next.methods.forEach((key, to) -> {
            // Members only named in the second hop still need to be reachable
            // from the first hop's namespace.
            String owner = inverseClass(key.owner());
            builder.mapMethodIfAbsent(owner, key.name(), key.descriptor(), to);
        });

        fields.forEach((key, to) -> {
            String owner = mapClass(key.owner());
            String descriptor = key.descriptor() == null ? null : remapper.mapDesc(key.descriptor());
            builder.mapField(key.owner(), key.name(), key.descriptor(), next.mapField(owner, to, descriptor));
        });

        next.fields.forEach((key, to) -> {
            String owner = inverseClass(key.owner());
            builder.mapFieldIfAbsent(owner, key.name(), key.descriptor(), to);
        });

        globalMethods.forEach((from, to) -> builder.mapMethodGlobally(from,
                next.globalMethods.getOrDefault(to, to)));
        next.globalMethods.forEach(builder.globalMethods::putIfAbsent);
        globalFields.forEach((from, to) -> builder.mapFieldGlobally(from, next.globalFields.getOrDefault(to, to)));
        next.globalFields.forEach(builder.globalFields::putIfAbsent);

        return builder.build();
    }

    /**
     * The class whose image under this set is {@code internalName}.
     *
     * <p>Indexed once rather than searched per call: composing two full game
     * tables asks this for every member of the second, and a linear search made
     * that tens of millions of string comparisons.
     */
    private String inverseClass(String internalName) {
        Map<String, String> inverse = inverseClasses;

        if (inverse == null) {
            Map<String, String> built = new HashMap<>();
            classes.forEach((from, to) -> built.putIfAbsent(to, from));
            inverseClasses = inverse = built;
        }

        return inverse.getOrDefault(internalName, internalName);
    }

    @Override
    public String toString() {
        return name + " (" + classes.size() + " classes, " + (methods.size() + globalMethods.size())
                + " methods, " + (fields.size() + globalFields.size()) + " fields)";
    }

    /** @param descriptor {@code null} means "any descriptor" */
    public record MemberKey(String owner, String name, String descriptor) {
    }

    /** Receives every entry of a set, for writing it somewhere. */
    public interface Visitor {
        void visitClass(String from, String to) throws java.io.IOException;

        void visitMethod(String owner, String name, String descriptor, String to) throws java.io.IOException;

        void visitField(String owner, String name, String descriptor, String to) throws java.io.IOException;

        void visitGlobalMethod(String name, String to) throws java.io.IOException;

        void visitGlobalField(String name, String to) throws java.io.IOException;
    }

    /** Hands every entry to {@code visitor}. */
    public void export(Visitor visitor) throws java.io.IOException {
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            visitor.visitClass(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<MemberKey, String> entry : methods.entrySet()) {
            visitor.visitMethod(entry.getKey().owner(), entry.getKey().name(), entry.getKey().descriptor(),
                    entry.getValue());
        }

        for (Map.Entry<MemberKey, String> entry : fields.entrySet()) {
            visitor.visitField(entry.getKey().owner(), entry.getKey().name(), entry.getKey().descriptor(),
                    entry.getValue());
        }

        for (Map.Entry<String, String> entry : globalMethods.entrySet()) {
            visitor.visitGlobalMethod(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, String> entry : globalFields.entrySet()) {
            visitor.visitGlobalField(entry.getKey(), entry.getValue());
        }
    }

    private final class SetRemapper extends Remapper {
        private final Function<String, List<String>> parents;

        SetRemapper(Function<String, List<String>> parents) {
            this.parents = parents;
        }

        @Override
        public String map(String internalName) {
            return mapClass(internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (name.startsWith("<")) {
                return name;
            }

            String mapped = lookupMethod(owner, name, descriptor);

            if (mapped == null && parents != null) {
                mapped = inherited(owner, name, descriptor, true, new HashSet<>());
            }

            return mapped != null ? mapped : name;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String mapped = lookupField(owner, name, descriptor);

            if (mapped == null && parents != null) {
                mapped = inherited(owner, name, descriptor, false, new HashSet<>());
            }

            return mapped != null ? mapped : name;
        }

        private String inherited(String owner, String name, String descriptor, boolean method, Set<String> seen) {
            // A name the compiler made is private to the class that has it:
            // Block's lambda$static$3 renumbered says nothing about the
            // lambda$static$3 of a mod's block, and following it there gave
            // Create's FluidTankBlock two methods of one name.
            if (owner == null || name.indexOf('$') >= 0 || !seen.add(owner) || seen.size() > 64) {
                return null;
            }

            for (String parent : parents.apply(owner)) {
                String mapped = method ? lookupMethod(parent, name, descriptor) : lookupField(parent, name, descriptor);

                if (mapped == null) {
                    mapped = inherited(parent, name, descriptor, method, seen);
                }

                if (mapped != null) {
                    return mapped;
                }
            }

            return null;
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor) {
            // A lambda implementing a remapped functional interface keeps the
            // interface method's name. Only the unique-identifier names can be
            // known without the interface in hand, and those are the ones that
            // change.
            String mapped = globalMethods.get(name);
            return mapped != null ? mapped : name;
        }

        @Override
        public Object mapValue(Object value) {
            // Strings in the constant pool: reflection and refmap-style names.
            return value instanceof String text ? mapString(text) : super.mapValue(value);
        }
    }

    public static final class Builder {
        private final String name;
        private final Map<String, String> classes = new LinkedHashMap<>();
        private final Map<MemberKey, String> methods = new HashMap<>();
        private final Map<MemberKey, String> fields = new HashMap<>();
        private final Map<String, String> globalMethods = new HashMap<>();
        private final Map<String, String> globalFields = new HashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder mapClass(String from, String to) {
            if (from != null && to != null && !from.equals(to)) {
                classes.put(from, to);
            }

            return this;
        }

        public Builder mapMethod(String owner, String from, String descriptor, String to) {
            if (owner != null && from != null && to != null) {
                methods.put(new MemberKey(owner, from, descriptor), to);
            }

            return this;
        }

        public Builder mapField(String owner, String from, String descriptor, String to) {
            if (owner != null && from != null && to != null) {
                fields.put(new MemberKey(owner, from, descriptor), to);
            }

            return this;
        }

        /** Maps a method by its name alone, wherever it appears. */
        public Builder mapMethodGlobally(String from, String to) {
            if (from != null && to != null && !from.equals(to)) {
                globalMethods.put(from, to);
            }

            return this;
        }

        /** Maps a field by its name alone, wherever it appears. */
        public Builder mapFieldGlobally(String from, String to) {
            if (from != null && to != null && !from.equals(to)) {
                globalFields.put(from, to);
            }

            return this;
        }

        Builder mapMethodIfAbsent(String owner, String from, String descriptor, String to) {
            if (owner != null && from != null && to != null) {
                methods.putIfAbsent(new MemberKey(owner, from, descriptor), to);
            }

            return this;
        }

        Builder mapFieldIfAbsent(String owner, String from, String descriptor, String to) {
            if (owner != null && from != null && to != null) {
                fields.putIfAbsent(new MemberKey(owner, from, descriptor), to);
            }

            return this;
        }

        public MappingSet build() {
            return new MappingSet(name, classes, methods, fields, globalMethods, globalFields);
        }
    }
}
