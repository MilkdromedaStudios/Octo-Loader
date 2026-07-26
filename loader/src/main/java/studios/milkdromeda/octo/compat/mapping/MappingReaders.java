package studios.milkdromeda.octo.compat.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Readers for the mapping formats Minecraft modding has used.
 *
 * <p>All of them say the same thing in different syntax, so they all produce a
 * {@link MappingSet}. Which one a file is in is decided by its first line rather
 * than its extension, because renamed mapping files are common.
 */
public final class MappingReaders {
    private MappingReaders() {
    }

    public static MappingSet read(Path file, String fromNamespace, String toNamespace) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, file.getFileName().toString(), fromNamespace, toNamespace);
        }
    }

    public static MappingSet read(InputStream in, String name, String fromNamespace, String toNamespace)
            throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1 << 16);
        String first = reader.readLine();

        if (first == null) {
            return MappingSet.EMPTY;
        }

        reader.reset();

        if (first.startsWith("tiny\t2")) {
            return readTinyV2(reader, name, fromNamespace, toNamespace);
        }

        if (first.startsWith("tsrg2")) {
            return readTsrg2(reader, name);
        }

        if (first.startsWith("CL:") || first.startsWith("FD:") || first.startsWith("MD:")) {
            return readSrg(reader, name);
        }

        return readTsrg(reader, name);
    }

    /**
     * Tiny v2 — Fabric's format. Columns are namespaces; the header names them,
     * and the caller picks which two columns to map between.
     */
    static MappingSet readTinyV2(BufferedReader reader, String name, String fromNamespace, String toNamespace)
            throws IOException {
        MappingSet.Builder builder = MappingSet.builder(name);
        String header = reader.readLine();

        if (header == null) {
            return MappingSet.EMPTY;
        }

        List<String> namespaces = List.of(header.split("\t"));
        int from = indexOfNamespace(namespaces, fromNamespace, 3);
        int to = indexOfNamespace(namespaces, toNamespace, 4);

        if (from < 0 || to < 0) {
            throw new IOException("mapping file " + name + " has no " + fromNamespace + " -> " + toNamespace
                    + " columns (found " + namespaces.subList(Math.min(3, namespaces.size()), namespaces.size()) + ")");
        }

        String currentClassFrom = null;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            String[] parts = line.split("\t", -1);

            if (line.charAt(0) == 'c') {
                // "c", name0, name1, ... — so namespace n sits two columns left
                // of where it does on a member line.
                currentClassFrom = column(parts, from - 2);
                builder.mapClass(currentClassFrom, column(parts, to - 2));
            } else if (line.startsWith("\t") && parts.length > 3 && currentClassFrom != null) {
                // "", kind, descriptor, name0, name1, ...
                String kind = parts[1];
                String descriptor = parts[2];
                String memberFrom = column(parts, from);
                String memberTo = column(parts, to);

                if (kind.equals("m")) {
                    builder.mapMethod(currentClassFrom, memberFrom, descriptor, memberTo);
                } else if (kind.equals("f")) {
                    builder.mapField(currentClassFrom, memberFrom, descriptor, memberTo);
                }
            }
        }

        return builder.build();
    }

    private static String column(String[] parts, int index) {
        return index >= 0 && index < parts.length && !parts[index].isEmpty() ? parts[index] : null;
    }

    private static int indexOfNamespace(List<String> namespaces, String wanted, int fallback) {
        if (wanted != null) {
            for (int i = 3; i < namespaces.size(); i++) {
                if (namespaces.get(i).equalsIgnoreCase(wanted)) {
                    return i;
                }
            }

            return -1;
        }

        return fallback < namespaces.size() ? fallback : namespaces.size() - 1;
    }

    /** The original SRG format, {@code CL:}/{@code FD:}/{@code MD:} lines. Forge, 1.3–1.12. */
    static MappingSet readSrg(BufferedReader reader, String name) throws IOException {
        MappingSet.Builder builder = MappingSet.builder(name);
        String line;

        while ((line = reader.readLine()) != null) {
            String[] parts = line.trim().split("\\s+");

            if (parts.length < 3) {
                continue;
            }

            switch (parts[0].toUpperCase(Locale.ROOT)) {
                case "CL:" -> builder.mapClass(parts[1], parts[2]);
                case "FD:" -> {
                    Member from = Member.split(parts[1]);
                    Member to = Member.split(parts[2]);

                    if (from != null && to != null) {
                        builder.mapField(from.owner(), from.name(), null, to.name());
                    }
                }
                case "MD:" -> {
                    if (parts.length < 5) {
                        continue;
                    }

                    Member from = Member.split(parts[1]);
                    Member to = Member.split(parts[3]);

                    if (from != null && to != null) {
                        builder.mapMethod(from.owner(), from.name(), parts[2], to.name());
                    }
                }
                default -> {
                    // PK: lines and anything else carry no member information.
                }
            }
        }

        return builder.build();
    }

    /** TSRG — SRG with the class name factored out and members indented. */
    static MappingSet readTsrg(BufferedReader reader, String name) throws IOException {
        MappingSet.Builder builder = MappingSet.builder(name);
        String currentClass = null;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            boolean indented = line.charAt(0) == '\t' || line.startsWith("    ");
            String[] parts = line.trim().split("\\s+");

            if (!indented) {
                if (parts.length >= 2) {
                    currentClass = parts[0];
                    builder.mapClass(parts[0], parts[1]);
                }

                continue;
            }

            if (currentClass == null) {
                continue;
            }

            if (parts.length == 2) {
                builder.mapField(currentClass, parts[0], null, parts[1]);
            } else if (parts.length >= 3) {
                builder.mapMethod(currentClass, parts[0], parts[1], parts[2]);
            }
        }

        return builder.build();
    }

    /** TSRG2 — as TSRG, with a header line and optional parameter names. */
    static MappingSet readTsrg2(BufferedReader reader, String name) throws IOException {
        MappingSet.Builder builder = MappingSet.builder(name);
        reader.readLine(); // header
        String currentClass = null;
        String line;

        while ((line = reader.readLine()) != null) {
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
                    currentClass = parts[0];
                    builder.mapClass(parts[0], parts[1]);
                }
            } else if (depth == 1 && currentClass != null) {
                if (parts.length == 2) {
                    builder.mapField(currentClass, parts[0], null, parts[1]);
                } else if (parts.length >= 3 && parts[1].startsWith("(")) {
                    builder.mapMethod(currentClass, parts[0], parts[1], parts[2]);
                } else if (parts.length >= 3) {
                    builder.mapField(currentClass, parts[0], parts[1], parts[2]);
                }
            }

            // depth >= 2 is parameter and local-variable naming, which Octo does not need.
        }

        return builder.build();
    }

    /** {@code net/minecraft/Block/blockID} splits into owner and member. */
    private record Member(String owner, String name) {
        static Member split(String qualified) {
            int slash = qualified.lastIndexOf('/');
            return slash <= 0 ? null : new Member(qualified.substring(0, slash), qualified.substring(slash + 1));
        }
    }
}
