package net.neoforged.neoforge.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The render layers a block model draws in, as NeoForge had them until 26.x.
 *
 * <p>NeoForge 26 dropped the class with the layered chunk renderer it served;
 * mods from before still ask a model for its layers and walk them, Create's
 * buffer building among them. Octo answers with the layers a model's
 * geometry needs on this game ({@code LegacyModels}). Its members take
 * {@code Object} where they took a render type, since Octo is not built
 * against the game; a call naming the render type reaches them all the same.
 */
public final class ChunkRenderTypeSet implements Iterable<Object> {
    private static final ChunkRenderTypeSet NONE = new ChunkRenderTypeSet(List.of());

    private final List<Object> types;

    private ChunkRenderTypeSet(Collection<?> types) {
        Set<Object> unique = new LinkedHashSet<>(types);
        unique.remove(null);
        this.types = List.copyOf(unique);
    }

    public static ChunkRenderTypeSet none() {
        return NONE;
    }

    /** Every layer there is; on this game, the ones a model would be drawn in are asked of the model. */
    public static ChunkRenderTypeSet all() {
        return NONE;
    }

    public static ChunkRenderTypeSet of(Object... types) {
        return new ChunkRenderTypeSet(List.of(types));
    }

    public static ChunkRenderTypeSet of(Collection<?> types) {
        return new ChunkRenderTypeSet(types);
    }

    public static ChunkRenderTypeSet union(ChunkRenderTypeSet... sets) {
        List<Object> all = new ArrayList<>();

        for (ChunkRenderTypeSet set : sets) {
            all.addAll(set.types);
        }

        return new ChunkRenderTypeSet(all);
    }

    public static ChunkRenderTypeSet union(Collection<ChunkRenderTypeSet> sets) {
        return union(sets.toArray(ChunkRenderTypeSet[]::new));
    }

    public static ChunkRenderTypeSet intersection(ChunkRenderTypeSet... sets) {
        if (sets.length == 0) {
            return NONE;
        }

        List<Object> common = new ArrayList<>(sets[0].types);

        for (ChunkRenderTypeSet set : sets) {
            common.retainAll(set.types);
        }

        return new ChunkRenderTypeSet(common);
    }

    public boolean isEmpty() {
        return types.isEmpty();
    }

    public boolean contains(Object type) {
        return types.contains(type);
    }

    public List<Object> asList() {
        return types;
    }

    @Override
    public Iterator<Object> iterator() {
        return types.iterator();
    }
}
