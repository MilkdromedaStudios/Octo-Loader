package studios.milkdromeda.octo.transform;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * What Octo hands back where it has nothing to hand back.
 *
 * <p>Two things produce these: a call to a loader-API member Octo has not
 * implemented, and a method on a class Octo stood in for because it is not there
 * at all. Both used to answer null for everything that was not a number, and
 * both were wrong in the same way and for the same caller.
 *
 * <p>jei is the example. {@code ModList.getAllScanData()} was one of these gaps,
 * the answer was null, and jei took it straight to {@code .iterator()} and did
 * not construct — so a loader gap became a mod that is simply not running, one
 * jar and one stack trace away from anything that named the cause. Nothing said
 * in the shape the caller asked for is an answer it can handle: no scan data, no
 * entries, no value. Null is a second failure.
 *
 * <p>Null still stands for everything else. An empty string is not the same
 * claim as no string, and a made-up object of some type Octo knows nothing about
 * is a worse answer than an honest absence.
 */
final class Nothing {
    private Nothing() {
    }

    /**
     * Pushes one value of {@code type}, or nothing at all for {@code void}.
     *
     * <p>The collections are the mutable kinds. A caller that adds to what it
     * was given is the same caller null would have stopped, and swapping its
     * {@code NullPointerException} for an {@code UnsupportedOperationException}
     * is not a fix.
     */
    static void push(MethodVisitor visitor, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> {
            }
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT ->
                    visitor.visitInsn(Opcodes.ICONST_0);
            case Type.LONG -> visitor.visitInsn(Opcodes.LCONST_0);
            case Type.FLOAT -> visitor.visitInsn(Opcodes.FCONST_0);
            case Type.DOUBLE -> visitor.visitInsn(Opcodes.DCONST_0);
            case Type.ARRAY -> pushEmptyArray(visitor, type);
            default -> pushEmptyOrNull(visitor, type);
        }
    }

    private static void pushEmptyOrNull(MethodVisitor visitor, Type type) {
        switch (type.getInternalName()) {
            case "java/util/List", "java/util/Collection", "java/lang/Iterable",
                    "java/util/ArrayList" -> construct(visitor, "java/util/ArrayList");
            case "java/util/Set" -> construct(visitor, "java/util/LinkedHashSet");
            case "java/util/Map" -> construct(visitor, "java/util/LinkedHashMap");
            case "java/util/Optional" -> visitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/util/Optional", "empty", "()Ljava/util/Optional;", false);
            case "java/util/stream/Stream" -> visitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/util/stream/Stream", "empty", "()Ljava/util/stream/Stream;", true);
            default -> visitor.visitInsn(Opcodes.ACONST_NULL);
        }
    }

    private static void construct(MethodVisitor visitor, String type) {
        visitor.visitTypeInsn(Opcodes.NEW, type);
        visitor.visitInsn(Opcodes.DUP);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
    }

    private static void pushEmptyArray(MethodVisitor visitor, Type type) {
        Type element = Type.getType(type.getDescriptor().substring(1));
        visitor.visitInsn(Opcodes.ICONST_0);

        switch (element.getSort()) {
            case Type.BOOLEAN -> visitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN);
            case Type.CHAR -> visitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_CHAR);
            case Type.BYTE -> visitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
            case Type.SHORT -> visitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_SHORT);
            case Type.INT -> visitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
            case Type.LONG -> visitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
            case Type.FLOAT -> visitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
            case Type.DOUBLE -> visitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);

            // An array of arrays names its element by descriptor, not by
            // internal name; everything else names a class.
            default -> visitor.visitTypeInsn(Opcodes.ANEWARRAY,
                    element.getSort() == Type.ARRAY ? element.getDescriptor() : element.getInternalName());
        }
    }
}
