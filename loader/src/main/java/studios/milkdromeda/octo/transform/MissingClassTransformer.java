package studios.milkdromeda.octo.transform;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import studios.milkdromeda.octo.compat.api.Equivalents;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Points a reference to a class this Minecraft does not have at the nearest one
 * it does.
 *
 * <p>The gap this fills sits between the other two. {@link RemapTransformer}
 * handles a mod that speaks an older set of names, and needs to know which set.
 * {@link PhantomClasses} handles a class that has no counterpart at all, and
 * fabricates one. In between is the ordinary case of a class that is still
 * there under a different name or in a different package — {@code ItemRenderer}
 * moving out of the entity renderer package, {@code MinecartFurnace} against
 * Yarn's {@code FurnaceMinecartEntity} — where the right answer is neither a
 * whole-mod translation nor an empty stand-in, but one name substituted for
 * another.
 *
 * <p>Nothing is substituted unless the original is genuinely absent from the
 * running game, which is checked against the class loader that would have had to
 * resolve it. That is what lets one table be correct on every Minecraft version
 * at once: on a version that has the class, this transformer does nothing.
 */
public final class MissingClassTransformer implements Transformer {
    private static final OctoLog LOG = OctoLog.of(MissingClassTransformer.class);

    /**
     * Classes that cannot be referring to something the game is missing.
     *
     * <p>The game's own classes are the bulk of every launch and are by
     * definition consistent with themselves; Octo's are loaded by the parent and
     * never reach here.
     */
    private static final List<String> NEVER_REFERS_TO_MISSING = List.of(
            "net/minecraft/", "com/mojang/", "java/", "javax/", "jdk/", "sun/",
            "org/objectweb/asm/", "org/spongepowered/asm/", "studios/milkdromeda/octo/");

    private final Equivalents table;
    private final ByteScan scan;
    private final Map<String, String> decisions = new ConcurrentHashMap<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public MissingClassTransformer(Equivalents table) {
        this.table = table;
        this.scan = new ByteScan(table.mentionedClasses());
    }

    /** Every substitution this made, for the launch summary. */
    public Set<String> substitutions() {
        return new LinkedHashSet<>(reported);
    }

    @Override
    public String name() {
        return "missing-classes(" + table.size() + " entries)";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        if (scan.isEmpty()) {
            return false;
        }

        for (String prefix : NEVER_REFERS_TO_MISSING) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        if (!scan.matches(bytes)) {
            return bytes;
        }

        Substitutions substitutions = new Substitutions(className, context);
        ClassReader reader = new ClassReader(bytes);
        // COMPUTE_MAXS rather than COMPUTE_FRAMES, for the same reason the
        // remapper uses it: computing frames means loading every referenced type,
        // and a missing type is the whole reason this transformer ran.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassRemapper(writer, substitutions), 0);
        return substitutions.changed ? writer.toByteArray() : bytes;
    }

    /**
     * @return the name to use instead, which is {@code internalName} itself
     *         whenever the class is present or nothing better is known
     */
    private String resolve(String internalName, Predicate<String> classExists) {
        return decisions.computeIfAbsent(internalName, name -> {
            Equivalents.ClassEntry entry = table.classEntry(name);

            if (entry == null || entry.alternatives().isEmpty() || classExists.test(name)) {
                return name;
            }

            for (String alternative : entry.alternatives()) {
                if (classExists.test(alternative)) {
                    return alternative;
                }
            }

            // Nothing survives under any of the names. Left alone deliberately:
            // a stand-in of the right shape, planned from the mod's own bytecode,
            // is a better answer than a redirect to a class that is also absent.
            return name;
        });
    }

    private final class Substitutions extends Remapper {
        private final String className;
        private final TransformContext context;
        boolean changed;

        Substitutions(String className, TransformContext context) {
            this.className = className;
            this.context = context;
        }

        @Override
        public String map(String internalName) {
            if (internalName == null || internalName.equals(className)) {
                return internalName;
            }

            String replacement = resolve(internalName, context.classExists());

            if (replacement.equals(internalName)) {
                return internalName;
            }

            changed = true;
            String description = internalName.replace('/', '.') + " is not in this Minecraft; using "
                    + replacement.replace('/', '.') + " instead";

            if (reported.add(internalName + " -> " + replacement)) {
                LOG.info("{}", description);
            }

            context.note(description);
            return replacement;
        }
    }
}
