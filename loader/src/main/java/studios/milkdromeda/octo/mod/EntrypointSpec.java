package studios.milkdromeda.octo.mod;

/**
 * One entrypoint declaration, normalised across ecosystems.
 *
 * <p>Fabric and Quilt allow {@code com.example.Mod}, {@code com.example.Mod::method}
 * and {@code com.example.Mod::FIELD}; Forge and NeoForge name a class carrying
 * {@code @Mod} whose constructor is the entrypoint. Both collapse to a class name
 * plus an optional member.
 *
 * @param className   binary name of the class to load
 * @param memberName  method or field to use, or {@code null} to instantiate the class
 * @param phase       the lifecycle phase this entrypoint belongs to
 * @param source      the raw declaration, kept for diagnostics
 */
public record EntrypointSpec(String className, String memberName, String phase, String source) {
    public static EntrypointSpec parse(String declaration, String phase) {
        String text = declaration.trim();
        int separator = text.indexOf("::");

        if (separator < 0) {
            return new EntrypointSpec(text, null, phase, declaration);
        }

        return new EntrypointSpec(text.substring(0, separator), text.substring(separator + 2), phase, declaration);
    }

    public boolean isConstructor() {
        return memberName == null || memberName.equals("<init>");
    }

    @Override
    public String toString() {
        return memberName == null ? className : className + "::" + memberName;
    }
}
