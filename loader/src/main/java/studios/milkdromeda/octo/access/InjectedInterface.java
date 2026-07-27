package studios.milkdromeda.octo.access;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * An interface a class tweaker adds to a game class.
 *
 * <p>The declared name may carry generics — {@code FabricModel<TT;>} — and the
 * two halves go to different places. The class file's interface list holds
 * names, and a name containing {@code <} is not one: the JVM rejects the whole
 * class with {@code ClassFormatError: Illegal class name}, which is a crash
 * during the game's own bootstrap rather than anything a mod can survive. The
 * generics belong in the class's {@code Signature} attribute instead.
 *
 * @param rawName   the interface's internal name, generics removed
 * @param signature the field-type signature to append to the target class's own
 */
public record InjectedInterface(String rawName, String signature) {
    /**
     * @param declared the name as written in the class tweaker, with generics if any
     * @throws IllegalArgumentException when the name could never appear in a class file
     */
    public static InjectedInterface parse(String declared) {
        String name = declared.replace('.', '/');
        String raw = rawTypeOf(name);

        if (!isLegalInternalName(raw)) {
            throw new IllegalArgumentException("\"" + declared + "\" is not a class name");
        }

        return new InjectedInterface(raw, "L" + name + ";");
    }

    public boolean hasGenerics() {
        return signature.indexOf('<') >= 0;
    }

    /** Reads the erased name out of a possibly-generic one, inner classes included. */
    private static String rawTypeOf(String name) {
        if (name.indexOf('<') < 0) {
            return name;
        }

        try {
            RawType rawType = new RawType();
            new SignatureReader("L" + name + ";").accept(rawType);
            return rawType.text.toString();
        } catch (RuntimeException e) {
            // A signature ASM will not parse is one the JVM would not accept
            // either; fall back to the text before the first type argument and
            // let the name check below decide.
            return name.substring(0, name.indexOf('<'));
        }
    }

    /**
     * Whether a name can appear as a class in a constant pool.
     *
     * <p>Deliberately strict. Anything this rejects would otherwise become an
     * unloadable game class, and one unloadable game class is a dead launch —
     * so a rule Octo cannot express safely is a rule it declines.
     */
    static boolean isLegalInternalName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.endsWith("/")
                || name.contains("//")) {
            return false;
        }

        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);

            if (character == '.' || character == ';' || character == '[' || character == '<'
                    || character == '>' || Character.isWhitespace(character)) {
                return false;
            }
        }

        return true;
    }

    private static final class RawType extends SignatureVisitor {
        private final StringBuilder text = new StringBuilder();

        RawType() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitClassType(String name) {
            text.append(name);
        }

        @Override
        public void visitInnerClassType(String name) {
            text.append('$').append(name);
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            // The type arguments are the part being dropped.
            return new SignatureVisitor(Opcodes.ASM9) {
            };
        }
    }
}
