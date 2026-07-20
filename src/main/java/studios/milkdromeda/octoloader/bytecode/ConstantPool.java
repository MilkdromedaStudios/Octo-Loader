package studios.milkdromeda.octoloader.bytecode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Tiny standalone class-file constant pool reader. Octo needs the classes and
 * members a class file references (for the API coverage index and the
 * removed-API checks), which live entirely in {@code CONSTANT_Class},
 * {@code CONSTANT_*ref} and {@code CONSTANT_NameAndType} entries — no bytecode
 * library required, and it works identically in the agent context.
 */
public final class ConstantPool {
    private ConstantPool() {
    }

    /** A field or method reference found in a constant pool. */
    public record MemberRef(String owner, String name, String desc, boolean method) {
    }

    /** Everything a class file references: class names plus member refs. */
    public record ClassRefs(Set<String> classes, Set<MemberRef> members) {
    }

    public static Set<String> referencedClasses(InputStream classFile) throws IOException {
        return scan(classFile).classes();
    }

    /** Scans every non-META-INF class file in a jar; keys are jar entry names. */
    public static Map<String, ClassRefs> scanJar(Path jar) throws IOException {
        Map<String, ClassRefs> result = new LinkedHashMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")
                        || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(entry)) {
                    result.put(entry.getName(), scan(in));
                }
            }
        }
        return result;
    }

    public static ClassRefs scan(byte[] classFile) throws IOException {
        return scan(new ByteArrayInputStream(classFile));
    }

    public static ClassRefs scan(InputStream classFile) throws IOException {
        DataInputStream in = new DataInputStream(classFile);
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort(); // minor version
        in.readUnsignedShort(); // major version

        int count = in.readUnsignedShort();
        Map<Integer, String> utf8 = new HashMap<>();
        Set<Integer> classNameIndices = new HashSet<>();
        Map<Integer, Integer> classNameByIndex = new HashMap<>();   // pool index of Class → utf8 index
        Map<Integer, int[]> nameAndTypes = new HashMap<>();          // pool index of NameAndType → {name, desc}
        Set<int[]> memberRefs = new HashSet<>();                     // {class index, nat index, isMethod}

        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> { // Utf8: u2 length + bytes
                    byte[] bytes = new byte[in.readUnsignedShort()];
                    in.readFully(bytes);
                    utf8.put(i, new String(bytes, StandardCharsets.UTF_8));
                }
                case 7 -> { // Class: u2 name index
                    int nameIndex = in.readUnsignedShort();
                    classNameIndices.add(nameIndex);
                    classNameByIndex.put(i, nameIndex);
                }
                case 8, 16, 19, 20 -> in.readUnsignedShort(); // String, MethodType, Module, Package: u2
                case 3, 4 -> in.readInt();                    // Integer, Float: u4
                case 9, 10, 11 -> {                           // Fieldref, Methodref, InterfaceMethodref
                    int classIndex = in.readUnsignedShort();
                    int natIndex = in.readUnsignedShort();
                    memberRefs.add(new int[]{classIndex, natIndex, tag == 9 ? 0 : 1});
                }
                case 12 -> {                                  // NameAndType: u2 name + u2 desc
                    int nameIndex = in.readUnsignedShort();
                    int descIndex = in.readUnsignedShort();
                    nameAndTypes.put(i, new int[]{nameIndex, descIndex});
                }
                case 17, 18 -> in.readInt();                  // (Invoke)Dynamic: u2+u2
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

        Set<String> classes = new HashSet<>();
        for (int index : classNameIndices) {
            String name = unwrap(utf8.get(index));
            if (name != null && !name.isEmpty()) classes.add(name);
        }

        Set<MemberRef> members = new HashSet<>();
        for (int[] ref : memberRefs) {
            Integer ownerNameIndex = classNameByIndex.get(ref[0]);
            int[] nat = nameAndTypes.get(ref[1]);
            if (ownerNameIndex == null || nat == null) continue;
            String owner = unwrap(utf8.get(ownerNameIndex));
            String name = utf8.get(nat[0]);
            String desc = utf8.get(nat[1]);
            if (owner == null || owner.isEmpty() || name == null || desc == null) continue;
            members.add(new MemberRef(owner, name, desc, ref[2] == 1));
        }
        return new ClassRefs(classes, members);
    }

    /** Array descriptors like {@code "[Lcom/example/Foo;"} — unwrap to the element type. */
    private static String unwrap(String name) {
        if (name == null) return null;
        while (name.startsWith("[")) name = name.substring(1);
        if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
        return name;
    }
}
