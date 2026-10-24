package net.minecraft.octotest;

/**
 * Stands in for 26.x's {@code Identifier}: its constructor is private, and it
 * is built through static methods, which 1.20 mods never call.
 */
public final class Id3 {
    private final String namespace;
    private final String path;

    private Id3(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static Id3 fromNamespaceAndPath(String namespace, String path) {
        return new Id3(namespace, path);
    }

    /** Answers null where the constructor would have thrown; never what a constructor call means. */
    public static Id3 tryBuild(String namespace, String path) {
        return null;
    }

    public static Id3 parse(String text) {
        int colon = text.indexOf(':');
        return colon < 0 ? new Id3("minecraft", text) : new Id3(text.substring(0, colon), text.substring(colon + 1));
    }

    public static Id3 withDefaultNamespace(String path) {
        return new Id3("minecraft", path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
