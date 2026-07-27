package studios.milkdromeda.octo.access;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Every widening asked for by every mod, merged into one table.
 *
 * <p>Fabric mods ship {@code .accesswidener} files and Forge mods ship
 * {@code accesstransformer.cfg}; both say the same thing in different words —
 * "this game class has a private member I need to reach". Neither loader can
 * read the other's file, which is one of the reasons a Fabric mod and a Forge
 * mod cannot share a game. Octo reads both into this table and applies it once,
 * so a class widened by a Forge mod is also open to a Fabric one.
 *
 * <p>Names are internal ({@code net/minecraft/client/Minecraft}) and in the
 * running game's namespace; the readers do any translation on the way in.
 */
public final class AccessRules {
    public static final AccessRules EMPTY =
            new AccessRules(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    private final Map<String, Access> classes;
    private final Map<String, Access> methods;
    private final Map<String, Access> fields;
    /** owner -> access applied to every method it declares (Forge's {@code *()}). */
    private final Map<String, Access> allMethods;
    /** owner -> access applied to every field it declares (Forge's {@code *}). */
    private final Map<String, Access> allFields;
    /** owner -> interfaces a class tweaker adds to it. */
    private final Map<String, Set<InjectedInterface>> injectedInterfaces;

    private AccessRules(Map<String, Access> classes, Map<String, Access> methods, Map<String, Access> fields,
            Map<String, Access> allMethods, Map<String, Access> allFields,
            Map<String, Set<InjectedInterface>> injectedInterfaces) {
        this.classes = classes;
        this.methods = methods;
        this.fields = fields;
        this.allMethods = allMethods;
        this.allFields = allFields;
        this.injectedInterfaces = injectedInterfaces;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return classes.isEmpty() && methods.isEmpty() && fields.isEmpty()
                && allMethods.isEmpty() && allFields.isEmpty() && injectedInterfaces.isEmpty();
    }

    public int size() {
        return classes.size() + methods.size() + fields.size() + allMethods.size() + allFields.size()
                + injectedInterfaces.size();
    }

    /** The classes any rule mentions — the loader's cheap opt-out before parsing bytecode. */
    public Set<String> targets() {
        return classes.keySet();
    }

    public boolean touches(String internalName) {
        return classes.containsKey(internalName) || injectedInterfaces.containsKey(internalName);
    }

    /** The interfaces to add to a class, in declaration order. */
    public Set<InjectedInterface> interfacesFor(String internalName) {
        return injectedInterfaces.getOrDefault(internalName, Set.of());
    }

    public Access forClass(String internalName) {
        return classes.getOrDefault(internalName, Access.NONE);
    }

    public Access forMethod(String owner, String name, String descriptor) {
        return member(methods, allMethods, owner, name, descriptor);
    }

    public Access forField(String owner, String name, String descriptor) {
        return member(fields, allFields, owner, name, descriptor);
    }

    private static Access member(Map<String, Access> exact, Map<String, Access> wildcards,
            String owner, String name, String descriptor) {
        Access access = wildcards.getOrDefault(owner, Access.NONE);
        Access byDescriptor = exact.get(key(owner, name, descriptor));

        if (byDescriptor != null) {
            access = access.merge(byDescriptor);
        }

        // A rule written without a descriptor covers every overload of the name,
        // which is what Forge's transformer file means and what a hand-written
        // widener usually intends.
        Access byName = exact.get(key(owner, name, null));

        return byName == null ? access : access.merge(byName);
    }

    private static String key(String owner, String name, String descriptor) {
        return descriptor == null ? owner + "." + name : owner + "." + name + descriptor;
    }

    @Override
    public String toString() {
        return size() + " access rule(s) over " + classes.size() + " class(es)";
    }

    public static final class Builder {
        private final Map<String, Access> classes = new LinkedHashMap<>();
        private final Map<String, Access> methods = new HashMap<>();
        private final Map<String, Access> fields = new HashMap<>();
        private final Map<String, Access> allMethods = new HashMap<>();
        private final Map<String, Access> allFields = new HashMap<>();
        private final Map<String, Set<InjectedInterface>> injectedInterfaces = new LinkedHashMap<>();

        /**
         * Records an interface a class tweaker adds to a game class.
         *
         * <p>Fabric API uses this to make vanilla classes implement its own
         * interfaces, which is what lets a mod cast an {@code Entity} to one of
         * them. Without it the cast is a {@code ClassCastException} at the first
         * call rather than anything the mod can guard against.
         */
        public Builder injectInterface(String owner, InjectedInterface added) {
            injectedInterfaces.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(added);
            return this;
        }

        public Builder widenClass(String internalName, Access access) {
            merge(classes, internalName, access);
            return this;
        }

        public Builder widenMethod(String owner, String name, String descriptor, Access access) {
            merge(methods, key(owner, name, descriptor), access);
            // Reaching a member means reaching the class that holds it.
            return widenClass(owner, Access.PUBLIC);
        }

        public Builder widenField(String owner, String name, String descriptor, Access access) {
            merge(fields, key(owner, name, descriptor), access);
            return widenClass(owner, Access.PUBLIC);
        }

        public Builder widenAllMethods(String owner, Access access) {
            merge(allMethods, owner, access);
            return widenClass(owner, Access.PUBLIC);
        }

        public Builder widenAllFields(String owner, Access access) {
            merge(allFields, owner, access);
            return widenClass(owner, Access.PUBLIC);
        }

        public Builder merge(AccessRules other) {
            other.classes.forEach((name, access) -> merge(classes, name, access));
            other.methods.forEach((name, access) -> merge(methods, name, access));
            other.fields.forEach((name, access) -> merge(fields, name, access));
            other.allMethods.forEach((name, access) -> merge(allMethods, name, access));
            other.allFields.forEach((name, access) -> merge(allFields, name, access));
            other.injectedInterfaces.forEach((owner, interfaces) ->
                    interfaces.forEach(name -> injectInterface(owner, name)));
            return this;
        }

        private static void merge(Map<String, Access> target, String key, Access access) {
            if (access == null || access.isNothing()) {
                return;
            }

            target.merge(key, access, Access::merge);
        }

        public boolean isEmpty() {
            return classes.isEmpty() && methods.isEmpty() && fields.isEmpty()
                    && allMethods.isEmpty() && allFields.isEmpty() && injectedInterfaces.isEmpty();
        }

        public AccessRules build() {
            if (isEmpty()) {
                return EMPTY;
            }

            Map<String, Set<InjectedInterface>> interfaces = new LinkedHashMap<>();
            injectedInterfaces.forEach((owner, names) -> interfaces.put(owner, Set.copyOf(names)));

            return new AccessRules(Map.copyOf(classes), Map.copyOf(methods), Map.copyOf(fields),
                    Map.copyOf(allMethods), Map.copyOf(allFields), Map.copyOf(interfaces));
        }
    }
}
