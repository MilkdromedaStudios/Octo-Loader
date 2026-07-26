package studios.milkdromeda.octo.compat.api;

/**
 * One documented API change between two versions of the game.
 *
 * <p>Renames that mapping files cover are handled by remapping. These rules
 * cover the changes mappings cannot express: a method that moved to another
 * class, a method that became static, a field that turned into a getter, an
 * API that was deleted and needs a stand-in.
 *
 * @param kind        what changed
 * @param owner       internal name of the class the old member lived on
 * @param name        old member name
 * @param descriptor  old descriptor, or {@code null} to match any
 * @param newOwner    internal name of the replacement's owner
 * @param newName     replacement member name
 * @param newDescriptor replacement descriptor, or {@code null} to keep the old one
 * @param note        human-readable explanation, shown in the compatibility report
 */
public record ApiRule(Kind kind, String owner, String name, String descriptor,
                      String newOwner, String newName, String newDescriptor, String note) {
    public enum Kind {
        /** The class was renamed or moved. */
        CLASS_MOVED,
        /** The method kept its shape but changed name or owner. */
        METHOD_MOVED,
        /** The method became static, dropping the receiver. */
        METHOD_TO_STATIC,
        /** A field became a method call. */
        FIELD_TO_METHOD,
        /** The member is gone; calls are redirected to a shim that does the old thing. */
        REDIRECT_TO_SHIM,
        /** The member is gone with no replacement; calls become a no-op returning a default. */
        REMOVED
    }

    public boolean matchesMethod(String callOwner, String callName, String callDescriptor) {
        if (!owner.equals(callOwner) || !name.equals(callName)) {
            return false;
        }

        return descriptor == null || descriptor.equals(callDescriptor);
    }

    public boolean matchesField(String callOwner, String callName, String callDescriptor) {
        if (!owner.equals(callOwner) || !name.equals(callName)) {
            return false;
        }

        return descriptor == null || descriptor.equals(callDescriptor);
    }

    public String describe() {
        String from = owner + "." + name;
        String to = (newOwner == null ? owner : newOwner) + "." + (newName == null ? name : newName);
        return kind == Kind.REMOVED ? from + " removed" : from + " -> " + to;
    }
}
