package studios.milkdromeda.octo.transform;

/** A single rewrite applied to a class as it is loaded. */
public interface Transformer {
    String name();

    /**
     * @param className internal name, e.g. {@code com/example/Mod}
     * @param bytes     the class as it stands after earlier transformers
     * @return the rewritten class, or {@code bytes} unchanged
     */
    byte[] transform(String className, byte[] bytes, TransformContext context);

    /** Lets a transformer opt out cheaply before the class is parsed. */
    default boolean handles(String className, TransformContext context) {
        return true;
    }
}
