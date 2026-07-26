package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

import studios.milkdromeda.octo.compat.mapping.MappingSet;

/**
 * Rewrites every reference a mod makes to the game into the names the running
 * game actually uses.
 *
 * <p>This is what turns a call to {@code func_71410_x()} into a call to
 * {@code getInstance()}. The mapping set handed in has already been composed
 * from however many hops it took to get from the mod's era to this one.
 */
public final class RemapTransformer implements Transformer {
    private final MappingSet mappings;

    public RemapTransformer(MappingSet mappings) {
        this.mappings = mappings;
    }

    public MappingSet mappings() {
        return mappings;
    }

    @Override
    public String name() {
        return "remap(" + mappings.name() + ")";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return !mappings.isEmpty();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        // COMPUTE_MAXS rather than COMPUTE_FRAMES: frame computation needs to load
        // every referenced type, and a mod being translated forward is precisely
        // the case where some of those types do not exist yet.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassRemapper(writer, mappings.remapper()), 0);
        context.note("remapped through " + mappings.name());
        return writer.toByteArray();
    }
}
