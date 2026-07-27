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
 */
public record Access(boolean makePublic, boolean makeProtected, boolean removeFinal, boolean makeFinal) {
    public static final Access NONE = new Access(false, false, false, false);
    public static final Access PUBLIC = new Access(true, false, false, false);
    public static final Access PROTECTED = new Access(false, true, false, false);
    /** Fabric's {@code extendable}: visible enough to subclass, and not {@code final}. */
    public static final Access EXTENDABLE = new Access(false, true, true, false);
    /** Fabric's {@code mutable}: a {@code final} field becomes writable. */
    public static final Access MUTABLE = new Access(false, false, true, false);

    public boolean isNothing() {
        return equals(NONE);
    }

    /** Widenings accumulate: two mods each opening part of a class both get what they asked for. */
    public Access merge(Access other) {
        if (other == null || other.isNothing()) {
            return this;
        }

        return new Access(makePublic || other.makePublic,
                makeProtected || other.makeProtected,
                removeFinal || other.removeFinal,
                // Never final against an explicit widening; see the class comment.
                (makeFinal || other.makeFinal) && !(removeFinal || other.removeFinal));
    }

    /** Rewrites one access-flag word. */
    public int applyTo(int access) {
        int result = access;

        if (makePublic) {
            result = (result & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        } else if (makeProtected && (result & Opcodes.ACC_PUBLIC) == 0) {
            result = (result & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED;
        }

        if (removeFinal) {
            result &= ~Opcodes.ACC_FINAL;
        } else if (makeFinal) {
            result |= Opcodes.ACC_FINAL;
        }

        return result;
    }
}
