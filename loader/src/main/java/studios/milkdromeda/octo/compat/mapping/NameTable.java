package studios.milkdromeda.octo.compat.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.Type;

/**
 * One published name table, keyed by Mojang's obfuscated names.
 *
 * <p>Every table Minecraft modding has relied on since 1.14 is, underneath,
 * "obfuscated name to something": Mojang's own deobfuscation maps, Fabric's
 * intermediary, Forge's SRG. Keyed the same way, any two of them can be joined
 * on the obfuscated name, which is how one table is translated into another
 * without anyone having published that pair. Descriptors are kept in the
 * obfuscated namespace too, so overloads stay apart.
 */
final class NameTable {
    /** obfuscated class to name. */
    final Map<String, String> classes = new HashMap<>();
    /** {@code owner.name(desc)}, all obfuscated, to name. */
    final Map<String, String> methods = new HashMap<>();
    /** {@code owner.name}, obfuscated, to name. */
    final Map<String, String> fields = new HashMap<>();
    /** {@code owner.name} to the field's obfuscated descriptor, where a table says. */
    final Map<String, String> fieldDescriptors = new HashMap<>();

    static String methodKey(String owner, String name, String descriptor) {
        return owner + "." + name + descriptor;
    }

    static String fieldKey(String owner, String name) {
        return owner + "." + name;
    }

    /** A descriptor in this table's obfuscated namespace, rewritten into its names. */
    String mapDescriptor(String descriptor) {
        Type type = Type.getType(descriptor);

        if (type.getSort() == Type.METHOD) {
            StringBuilder out = new StringBuilder("(");

            for (Type argument : type.getArgumentTypes()) {
                out.append(mapType(argument));
            }

            return out.append(')').append(mapType(type.getReturnType())).toString();
        }

        return mapType(type);
    }

    private String mapType(Type type) {
        return switch (type.getSort()) {
            case Type.OBJECT -> "L" + classes.getOrDefault(type.getInternalName(), type.getInternalName()) + ";";
            case Type.ARRAY -> "[".repeat(type.getDimensions()) + mapType(type.getElementType());
            default -> type.getDescriptor();
        };
    }

    // ---------------------------------------------------------------- readers

    /**
     * Fabric's tiny v2, reading the {@code official} column against another.
     * Member descriptors in tiny v2 are written in the first namespace, which in
     * every published intermediary file is {@code official}.
     */
    static NameTable readTiny(BufferedReader reader, String target) throws IOException {
        NameTable table = new NameTable();
        String header = reader.readLine();

        if (header == null || !header.startsWith("tiny\t2")) {
            throw new IOException("not a tiny v2 file");
        }

        String[] namespaces = header.split("\t");
        int from = -1;
        int to = -1;

        for (int i = 3; i < namespaces.length; i++) {
            if (namespaces[i].equals("official")) {
                from = i - 3;
            } else if (namespaces[i].equals(target)) {
                to = i - 3;
            }
        }

        if (from != 0 || to < 0) {
            throw new IOException("tiny file has no official -> " + target + " columns");
        }

        String owner = null;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\t", -1);

            if (line.charAt(0) == 'c') {
                owner = parts[1 + from];
                table.classes.put(owner, parts[1 + to]);
            } else if (line.startsWith("\tm\t") && owner != null && parts.length > 3 + to) {
                table.methods.put(methodKey(owner, parts[3 + from], parts[2]), parts[3 + to]);
            } else if (line.startsWith("\tf\t") && owner != null && parts.length > 3 + to) {
                String key = fieldKey(owner, parts[3 + from]);
                table.fields.put(key, parts[3 + to]);
                table.fieldDescriptors.put(key, parts[2]);
            }
        }

        return table;
    }

    /**
     * Mojang's published deobfuscation map, in ProGuard's format, turned round
     * so that it is keyed by the obfuscated names like every other table.
     *
     * <pre>
     * net.minecraft.client.Minecraft -> fzz:
     *     net.minecraft.client.Minecraft instance -> I
     *     12:14:net.minecraft.client.Minecraft getInstance() -> Q
     * </pre>
     */
    static NameTable readProguard(BufferedReader reader) throws IOException {
        NameTable table = new NameTable();
        Map<String, String> deobfToObf = new HashMap<>();
        java.util.List<String[]> members = new java.util.ArrayList<>();
        String owner = null;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            if (!line.startsWith(" ")) {
                int arrow = line.indexOf(" -> ");

                if (arrow < 0 || !line.endsWith(":")) {
                    continue;
                }

                String named = line.substring(0, arrow).replace('.', '/');
                String obf = line.substring(arrow + 4, line.length() - 1).replace('.', '/');
                deobfToObf.put(named, obf);
                table.classes.put(obf, named);
                owner = obf;
                continue;
            }

            if (owner != null) {
                members.add(new String[] { owner, line.trim() });
            }
        }

        for (String[] member : members) {
            String text = member[1];
            int arrow = text.lastIndexOf(" -> ");

            if (arrow < 0) {
                continue;
            }

            String obfName = text.substring(arrow + 4);
            String left = text.substring(0, arrow);

            // Line numbers on methods: "12:14:void tick() -> a".
            while (!left.isEmpty() && Character.isDigit(left.charAt(0))) {
                int colon = left.indexOf(':');

                if (colon < 0) {
                    break;
                }

                left = left.substring(colon + 1);
            }

            int space = left.indexOf(' ');

            if (space < 0) {
                continue;
            }

            String returnType = left.substring(0, space);
            String rest = left.substring(space + 1);
            int open = rest.indexOf('(');

            if (open < 0) {
                String key = fieldKey(member[0], obfName);
                table.fields.put(key, rest);
                table.fieldDescriptors.put(key, toObfDescriptor(returnType, deobfToObf));
                continue;
            }

            String named = rest.substring(0, open);
            String arguments = rest.substring(open + 1, rest.length() - 1);
            StringBuilder descriptor = new StringBuilder("(");

            if (!arguments.isEmpty()) {
                for (String argument : arguments.split(",")) {
                    descriptor.append(toObfDescriptor(argument.trim(), deobfToObf));
                }
            }

            descriptor.append(')').append(toObfDescriptor(returnType, deobfToObf));
            table.methods.put(methodKey(member[0], obfName, descriptor.toString()), named);
        }

        return table;
    }

    private static String toObfDescriptor(String javaType, Map<String, String> deobfToObf) {
        int dimensions = 0;

        while (javaType.endsWith("[]")) {
            dimensions++;
            javaType = javaType.substring(0, javaType.length() - 2);
        }

        String element = switch (javaType) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> {
                String internal = javaType.replace('.', '/');
                yield "L" + deobfToObf.getOrDefault(internal, internal) + ";";
            }
        };

        return "[".repeat(dimensions) + element;
    }

    /**
     * Forge's MCPConfig {@code joined.tsrg} (tsrg2: obfuscated, SRG, id). Only
     * the first two columns matter here.
     */
    static NameTable readTsrg2(BufferedReader reader) throws IOException {
        NameTable table = new NameTable();
        String header = reader.readLine();

        if (header == null) {
            return table;
        }

        boolean v2 = header.startsWith("tsrg2");
        String owner = null;
        String line = v2 ? reader.readLine() : header;

        for (; line != null; line = reader.readLine()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            int depth = 0;

            while (depth < line.length() && line.charAt(depth) == '\t') {
                depth++;
            }

            String[] parts = line.trim().split("\\s+");

            if (depth == 0) {
                if (parts.length >= 2 && !parts[0].equals("static")) {
                    owner = parts[0];
                    table.classes.put(owner, parts[1]);
                }
            } else if (depth == 1 && owner != null) {
                if (parts.length >= 3 && parts[1].startsWith("(")) {
                    table.methods.put(methodKey(owner, parts[0], parts[1]), parts[2]);
                } else if (parts.length >= 2) {
                    table.fields.put(fieldKey(owner, parts[0]), parts[1]);
                }
            }
        }

        return table;
    }
}
