package studios.milkdromeda.octo.compat.mapping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The renames between two releases that no published table covers.
 *
 * <p>Every table Octo translates through ends at 1.21.11, the last release
 * Mojang obfuscated and so the last one anybody published mappings for. The
 * releases since ship their own names and nothing else, so what was renamed
 * between 1.21.11 and the game running now is in no file: the furnace's
 * {@code cookingProgress} became {@code cookingTimer}, and a mod translated
 * perfectly into 1.21.11's names still asks for a field that is not there.
 *
 * <p>The two jars say it, though. A class present in both, with a field gone
 * from one and a field of the same type and a related name new in the other,
 * renamed that field. This reads both, pairs what was removed with what was
 * added, class by class, and accepts a pair only when the names share a word —
 * {@code cooking}, {@code lit} — so that a member that was deleted is never
 * mistaken for an unrelated one that was added. What it cannot pair is left to
 * the bypass, as before.
 */
public final class VersionDiff {
    /** One class, as far as matching it against another release goes. */
    record Shape(String name, String superName, List<String> interfaces, List<Member> fields,
            List<Member> methods) {
    }

    record Member(String name, String descriptor, int access) {
        boolean isStatic() {
            return (access & Opcodes.ACC_STATIC) != 0;
        }

        boolean isMatchable() {
            return name.charAt(0) != '<' && !name.startsWith("lambda$")
                    && (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0;
        }
    }

    private static final Set<String> STOP_WORDS = Set.of("get", "set", "has", "can", "the", "and", "for", "with",
            "from", "into");

    private VersionDiff() {
    }

    /**
     * @param old     the older release's classes, in its own names
     * @param current the running game's classes
     * @return the renames, keyed by the older release's owner, name and
     *         descriptor
     */
    static MappingSet between(Map<String, Shape> old, Map<String, Shape> current, String name) {
        MappingSet.Builder builder = MappingSet.builder(name);

        for (Shape before : old.values()) {
            Shape after = current.get(before.name());

            if (after == null) {
                continue;
            }

            pair(before.fields(), after.fields(), (from, to) ->
                    builder.mapField(before.name(), from.name(), from.descriptor(), to.name()));
            pair(before.methods(), after.methods(), (from, to) ->
                    builder.mapMethod(before.name(), from.name(), from.descriptor(), to.name()));
        }

        return builder.build();
    }

    private interface Renamed {
        void accept(Member from, Member to);
    }

    /**
     * Pairs the members only one side has, by type and staticness, in the
     * order they are declared, keeping each pair whose names are related.
     */
    private static void pair(List<Member> before, List<Member> after, Renamed renamed) {
        Set<String> namesBefore = new HashSet<>();
        Set<String> namesAfter = new HashSet<>();
        before.forEach(member -> namesBefore.add(member.name()));
        after.forEach(member -> namesAfter.add(member.name()));

        Map<String, List<Member>> gone = new LinkedHashMap<>();
        Map<String, List<Member>> added = new LinkedHashMap<>();

        for (Member member : before) {
            if (member.isMatchable() && !namesAfter.contains(member.name())) {
                gone.computeIfAbsent(key(member), ignored -> new ArrayList<>()).add(member);
            }
        }

        for (Member member : after) {
            if (member.isMatchable() && !namesBefore.contains(member.name())) {
                added.computeIfAbsent(key(member), ignored -> new ArrayList<>()).add(member);
            }
        }

        gone.forEach((key, from) -> {
            List<Member> to = added.get(key);

            // Equal numbers on both sides, and few of them: more than that is a
            // class rewritten rather than renamed, and pairing by position
            // would be guessing.
            if (to == null || to.size() != from.size() || from.size() > 4) {
                return;
            }

            for (int i = 0; i < from.size(); i++) {
                if (related(from.get(i).name(), to.get(i).name())) {
                    renamed.accept(from.get(i), to.get(i));
                }
            }
        });
    }

    private static String key(Member member) {
        return (member.isStatic() ? "static " : "") + member.descriptor();
    }

    /**
     * Whether two names are the same thing renamed: one contains the other
     * ({@code CODEC} and {@code MAP_CODEC}), or they lead with the same word
     * ({@code cookingProgress} and {@code cookingTimer}). Sharing some other
     * word is not enough: {@code LONG_JUMPING_DIMENSIONS} going and
     * {@code BABY_DIMENSIONS} arriving is a field removed and an unrelated one
     * added.
     */
    static boolean related(String before, String after) {
        String a = before.toLowerCase(Locale.ROOT).replace("_", "");
        String b = after.toLowerCase(Locale.ROOT).replace("_", "");

        if (a.length() >= 3 && b.length() >= 3 && (a.contains(b) || b.contains(a))) {
            return true;
        }

        List<String> first = new ArrayList<>(words(before));
        List<String> second = new ArrayList<>(words(after));
        return !first.isEmpty() && !second.isEmpty() && first.get(0).equals(second.get(0));
    }

    private static Set<String> words(String name) {
        Set<String> out = new LinkedHashSet<>();

        for (String word : name.split("(?<=[a-z0-9])(?=[A-Z])|_|\\$")) {
            String lower = word.toLowerCase(Locale.ROOT);

            if (lower.length() >= 3 && !STOP_WORDS.contains(lower)) {
                out.add(lower);
            }
        }

        return out;
    }

    // ----------------------------------------------------------------- reading

    /** The game's own classes from jars that already carry their real names. */
    static Map<String, Shape> readNamed(List<Path> jars) throws IOException {
        Map<String, Shape> out = new HashMap<>();

        for (Path jar : jars) {
            read(jar, Function.identity(), (owner, name, descriptor) -> name, descriptor -> descriptor, out);
        }

        return out;
    }

    /** An obfuscated release's classes, named through that release's Mojang table. */
    static Map<String, Shape> readObfuscated(Path jar, NameTable names) throws IOException {
        Map<String, Shape> out = new HashMap<>();
        read(jar, obf -> names.classes.get(obf), (owner, name, descriptor) -> {
            String named = descriptor.startsWith("(")
                    ? names.methods.get(NameTable.methodKey(owner, name, descriptor))
                    : names.fields.get(NameTable.fieldKey(owner, name));
            return named != null ? named : name;
        }, names::mapDescriptor, out);
        return out;
    }

    private interface MemberNames {
        String name(String owner, String name, String descriptor);
    }

    private static void read(Path jar, Function<String, String> className, MemberNames memberName,
            Function<String, String> descriptor, Map<String, Shape> into) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();

                if (!path.endsWith(".class") || path.startsWith("META-INF/")) {
                    continue;
                }

                try (InputStream in = zip.getInputStream(entry)) {
                    Shape shape = shape(in.readAllBytes(), className, memberName, descriptor);

                    if (shape != null && isGame(shape.name())) {
                        into.putIfAbsent(shape.name(), shape);
                    }
                } catch (RuntimeException e) {
                    // One class that will not read is one class not compared.
                }
            }
        }
    }

    private static boolean isGame(String name) {
        return name.startsWith("net/minecraft/") || name.startsWith("com/mojang/");
    }

    private static Shape shape(byte[] bytes, Function<String, String> className, MemberNames memberName,
            Function<String, String> descriptor) {
        String[] header = new String[2];
        List<String> interfaces = new ArrayList<>();
        List<Member> fields = new ArrayList<>();
        List<Member> methods = new ArrayList<>();

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            private String owner;

            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] declared) {
                owner = name;
                header[0] = className.apply(name);
                header[1] = superName == null ? null : named(superName);

                if (declared != null) {
                    for (String iface : declared) {
                        interfaces.add(named(iface));
                    }
                }
            }

            private String named(String name) {
                String mapped = className.apply(name);
                return mapped != null ? mapped : name;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String fieldDescriptor, String signature,
                    Object value) {
                fields.add(new Member(memberName.name(owner, name, fieldDescriptor),
                        descriptor.apply(fieldDescriptor), access));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String methodDescriptor, String signature,
                    String[] exceptions) {
                methods.add(new Member(memberName.name(owner, name, methodDescriptor),
                        descriptor.apply(methodDescriptor), access));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return header[0] == null ? null : new Shape(header[0], header[1], List.copyOf(interfaces), fields, methods);
    }

    /**
     * The running game's class hierarchy, superclass then interfaces, for
     * finding a renamed member that a mod reaches through a subclass.
     */
    public static Map<String, List<String>> hierarchy(List<Path> jars) {
        Map<String, List<String>> out = new HashMap<>();

        try {
            readNamed(jars).forEach((name, shape) -> {
                List<String> parents = new ArrayList<>();

                if (shape.superName() != null) {
                    parents.add(shape.superName());
                }

                parents.addAll(shape.interfaces());
                out.put(name, parents);
            });
        } catch (IOException e) {
            // Without it, renames are found on the owner a mod names and no higher.
        }

        return out;
    }
}
