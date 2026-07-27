package studios.milkdromeda.octo.access;

import org.objectweb.asm.Opcodes;

/**
 * One widening: what a class or member is allowed to become.
 *
 * <p>Every rule only ever opens access up. Both formats Octo reads can in
 * principle narrow it — Forge's transformer file accepts {@code private}, and
 * {@code +f} can add {@code final} — but a loader that quietly makes something
 * less visible than the compiler saw breaks code that was correct, and nothing
 * in the wild relies on it, so narrowing is parsed and dropped.
 *
 * @param finalIfPrivate add {@code final}, but <em>only</em> to a method that
 *        was private. Opening a private method up would otherwise turn it into
 *        something subclasses can override, changing which implementation the
 *        game calls. Applying it any wider is not a subtlety but a crash: mark
 *        an inherited method {@code final} and every existing override becomes
 *        an {@code IncompatibleClassChangeError} the moment the subclass loads.
 */
public record Access(boolean makePublic, boolean makeProtected, boolean removeFinal, boolean finalIfPrivate) {
    public static final Access NONE = new Access(false, false, false, false);
    public static final Access PUBLIC = new Access(true, false, false, false);
    public static final Access PROTECTED = new Access(false, true, false, false);
    /** Fabric's {@code extendable} on a method: reachable to subclass, and not {@code final}. */
    public static final Access EXTENDABLE = new Access(false, true, true, false);
    /** Fabric's {@code mutable}: a {@code final} field becomes writable. */
    public static final Access MUTABLE = new Access(false, false, true, false);
    /** Fabric's {@code accessible} on a method. */
    public static final Access ACCESSIBLE_MEMBER = new Access(true, false, false, true);

    public boolean isNothing() {
        return equals(NONE);
    }

    /** Widenings accumulate: two mods each opening part of a class both get what they asked for. */
    public Access merge(Access other) {
        if (other == null || other.isNothing()) {
            return this;
        }

        boolean unfinal = removeFinal || other.removeFinal;

        return new Access(makePublic || other.makePublic,
                makeProtected || other.makeProtected,
                unfinal,
                // accessible and extendable on the same method means public and
                // not final, which is what Fabric resolves the pair to.
                (finalIfPrivate || other.finalIfPrivate) && !unfinal);
    }

    /** Rewrites the access flags of a class or a field. */
    public int applyTo(int access) {
        int result = access;

        if (makePublic) {
            result = (result & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        } else if (makeProtected && (result & Opcodes.ACC_PUBLIC) == 0) {
            result = (result & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED;
        }

        if (removeFinal) {
            result &= ~Opcodes.ACC_FINAL;
        }

        return result;
    }

    /**
     * Rewrites the access flags of a method.
     *
     * <p>Separate from {@link #applyTo(int)} because {@code final} on a method is
     * a statement about the whole hierarchy below it, so it can only be added
     * where it is provably safe: a private instance method, which nothing can be
     * overriding today.
     *
     * @param ownerIsInterface interfaces cannot have final methods at all
     */
    public int applyToMethod(int access, String name, boolean ownerIsInterface) {
        int result = applyTo(access);

        if (!finalIfPrivate || removeFinal) {
            return result;
        }

        boolean privateInstanceMethod = (access & Opcodes.ACC_PRIVATE) != 0
                && (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0
                && !ownerIsInterface
                && !name.startsWith("<");

        return privateInstanceMethod ? result | Opcodes.ACC_FINAL : result;
    }
}
