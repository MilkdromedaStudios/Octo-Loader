package studios.milkdromeda.octoloader.replace;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Applies a composed rename mapping to one class file. Frames are expanded for
 * the remapper and re-serialized as full frames — no class loading or
 * hierarchy computation happens, so this is safe at translation time when the
 * referenced classes do not exist on Octo's own classpath.
 */
public final class ApiRewriter {
    private ApiRewriter() {
    }

    public static byte[] rewrite(byte[] classBytes, Remapper remapper) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }
}
