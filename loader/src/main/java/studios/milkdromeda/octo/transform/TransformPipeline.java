package studios.milkdromeda.octo.transform;

import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.util.OctoLog;

/** The ordered set of rewrites applied to one mod's classes. */
public final class TransformPipeline {
    private static final OctoLog LOG = OctoLog.of(TransformPipeline.class);

    private final List<Transformer> transformers;

    private TransformPipeline(List<Transformer> transformers) {
        this.transformers = List.copyOf(transformers);
    }

    public static TransformPipeline of(List<Transformer> transformers) {
        return new TransformPipeline(transformers);
    }

    public static TransformPipeline empty() {
        return new TransformPipeline(List.of());
    }

    public boolean isEmpty() {
        return transformers.isEmpty();
    }

    public List<Transformer> transformers() {
        return transformers;
    }

    public TransformPipeline with(Transformer transformer) {
        List<Transformer> extended = new ArrayList<>(transformers);
        extended.add(transformer);
        return new TransformPipeline(extended);
    }

    public byte[] apply(String className, byte[] bytes, TransformContext context) {
        byte[] current = bytes;

        for (Transformer transformer : transformers) {
            if (!transformer.handles(className, context)) {
                continue;
            }

            try {
                byte[] result = transformer.transform(className, current, context);

                if (result != null) {
                    current = result;
                }
            } catch (RuntimeException | LinkageError e) {
                // One failed rewrite must not take the mod down: the untransformed
                // class may still work, and if it does not, the error surfaces at
                // the point of use with a far more useful stack trace than here.
                LOG.warn("transformer {} failed on {} ({}): {}", transformer.name(), className,
                        context.modId(), e.toString());
            }
        }

        return current;
    }

    @Override
    public String toString() {
        List<String> names = new ArrayList<>(transformers.size());
        transformers.forEach(transformer -> names.add(transformer.name()));
        return names.isEmpty() ? "no transformers" : String.join(" -> ", names);
    }
}
