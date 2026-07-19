package studios.milkdromeda.octoloader.translators.neoforge;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tiny standalone class-file constant pool reader. Octo only needs the set of
 * classes a class file references (for the API coverage index), which lives
 * entirely in {@code CONSTANT_Class} entries — no bytecode library required,
 * and it works identically in the agent context.
 */
final class ConstantPool {
    private ConstantPool() {
    }

    static Set<String> referencedClasses(InputStream classFile) throws IOException {
        DataInputStream in = new DataInputStream(classFile);
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort(); // minor version
        in.readUnsignedShort(); // major version

        int count = in.readUnsignedShort();
        Map<Integer, String> utf8 = new HashMap<>();
        Set<Integer> classNameIndices = new HashSet<>();

        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> { // Utf8: u2 length + bytes
                    byte[] bytes = new byte[in.readUnsignedShort()];
                    in.readFully(bytes);
                    utf8.put(i, new String(bytes, StandardCharsets.UTF_8));
                }
                case 7 -> classNameIndices.add(in.readUnsignedShort()); // Class: u2 name index
                case 8, 16, 19, 20 -> in.readUnsignedShort(); // String, MethodType, Module, Package: u2
                case 3, 4 -> in.readInt();                    // Integer, Float: u4
                case 9, 10, 11, 12, 17, 18 -> in.readInt();   // refs, NameAndType, (Invoke)Dynamic: u2+u2
                case 15 -> {                                  // MethodHandle: u1 kind + u2 index
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 5, 6 -> {                                // Long, Double: u8, take two pool slots
                    in.readLong();
                    i++;
                }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }

        Set<String> result = new HashSet<>();
        for (int index : classNameIndices) {
            String name = utf8.get(index);
            if (name == null) continue;
            // Array descriptors like "[Lcom/example/Foo;" — unwrap to the element type.
            while (name.startsWith("[")) name = name.substring(1);
            if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
            if (!name.isEmpty()) result.add(name);
        }
        return result;
    }
}
