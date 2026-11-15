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

    /**
     * Whether this rewrite asks the {@link MemberIndex} what other classes
     * declare. One that does is left out when a class is read only for its own
     * shape (see {@link MemberIndex#isReading}), so it must not be the one to
     * give a class a member, a supertype or an access level that other classes
     * link against.
     */
    default boolean readsMemberIndex() {
        return false;
    }
}
