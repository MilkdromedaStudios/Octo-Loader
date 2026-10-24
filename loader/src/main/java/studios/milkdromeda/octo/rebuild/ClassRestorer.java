package studios.milkdromeda.octo.rebuild;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;

import studios.milkdromeda.octo.compat.mapping.MappingProvider;
import studios.milkdromeda.octo.compat.mapping.MappingSet;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.mod.version.Version;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Adds back game classes this Minecraft no longer has, from the Minecraft a mod
 * was built for.
 *
 * <p>A stand-in for a missing class is a guess at its shape from how one mod
 * uses it, and some shapes cannot be guessed. Jade hands
 * {@code new ResourceLocation.Serializer()} to Gson, and Gson accepts it only
 * because the real class implements {@code JsonSerializer}; a stand-in that
 * does not is refused, and Jade does not start. The real class is not lost,
 * though. It is in the game jar of the release the mod was built for, and it is
 * code like any other: renamed into today's names, it calls today's
 * {@code Identifier.parse} exactly as it once called {@code ResourceLocation.parse}.
 *
 * <p>So a class a translated mod names, and that nothing here has, is looked
 * for in the old release's client jar — downloaded once — renamed, and kept in
 * {@code .octo/cache/restored} so later launches never open the old jar at all.
 * Only when no release a mod came from has it does the stand-in take over.
 *
 * <p>A client class is never added back to a dedicated server: it would come
 * with the rendering code it calls, and on a server a stand-in that does
 * nothing is the right answer.
 */
public final class ClassRestorer {
    private static final OctoLog LOG = OctoLog.of(ClassRestorer.class);

    /** Bumped whenever restored classes are written differently, so old copies are not reused. */
    private static final String FORMAT = "1";

    private static final List<String> GAME_PACKAGES = List.of("net/minecraft/", "com/mojang/");
    private static final List<String> CLIENT_PACKAGES = List.of(
            "net/minecraft/client/", "com/mojang/blaze3d/", "com/mojang/realmsclient/", "net/minecraft/realms/");

    private final MappingProvider mappings;
    private final List<String> releases;
    private final boolean server;
    private final Path cache;
    private final Map<String, Optional<Source>> sources = new ConcurrentHashMap<>();
    private final Set<String> absent = ConcurrentHashMap.newKeySet();
    private final Set<String> restored = ConcurrentHashMap.newKeySet();
    /** Game classes the classes added back name themselves, which may need adding back in turn. */
    private final Set<String> named = ConcurrentHashMap.newKeySet();

    /**
     * @param releases the releases to look in, newest first: the closer a
     *                 release is to this one, the less of its code has gone
     *                 stale
     */
    public ClassRestorer(MappingProvider mappings, List<String> releases, Side side, Path cache) {
        this.mappings = mappings;
        this.releases = List.copyOf(releases);
        this.server = side == Side.SERVER;
        this.cache = cache;
    }

    /** A restorer for the releases the given mods were built for, or {@code null} when none is known. */
    public static ClassRestorer forMods(List<LoadedMod> mods, MappingProvider mappings, Side side, Path cache) {
        Set<String> releases = new TreeSet<>(Comparator.comparing(Version::parse).reversed());

        for (LoadedMod mod : mods) {
            if (!mod.isFromAnotherVersion()) {
                continue;
            }

            String release = ModRebuilder.builtFor(mod);

            if (release != null) {
                releases.add(release);
            }
        }

        return releases.isEmpty() ? null : new ClassRestorer(mappings, new ArrayList<>(releases), side, cache);
    }

    public List<String> releases() {
        return releases;
    }

    /**
     * Whether a class added back names this one. A removed class tends to come
     * with removed neighbours no mod mentions directly — its own inner
     * classes, the helper it delegates to — and those are added back too.
     */
    public boolean isNamedByRestored(String internalName) {
        return named.contains(internalName);
    }

    /** Every class added back so far, for the launch summary. */
    public Set<String> restored() {
        return new LinkedHashSet<>(restored);
    }

    /**
     * @return the class, renamed into today's names, or {@code null} when no
     *         release this restorer knows has it
     */
    public byte[] restore(String internalName) {
        if (!isGameClass(internalName) || (server && isClientClass(internalName)) || absent.contains(internalName)) {
            return null;
        }

        for (String release : releases) {
            Path cached = cachedCopy(release, internalName);

            if (Files.isRegularFile(cached)) {
                try {
                    byte[] bytes = Files.readAllBytes(cached);
                    found(internalName, release, true, bytes);
                    return bytes;
                } catch (IOException e) {
                    LOG.debug("could not read {}: {}", cached, e.toString());
                }
            }

            Source source = sources.computeIfAbsent(release, this::open).orElse(null);

            if (source == null) {
                continue;
            }

            byte[] bytes = source.restore(internalName);

            if (bytes == null) {
                continue;
            }

            write(cached, bytes);
            found(internalName, release, false, bytes);
            return bytes;
        }

        absent.add(internalName);
        return null;
    }

    private void found(String internalName, String release, boolean fromCache, byte[] bytes) {
        if (restored.add(internalName.replace('/', '.') + " (from " + release + ")")) {
            LOG.info("added back {} from Minecraft {}{}", internalName.replace('/', '.'), release,
                    fromCache ? " (cached)" : "");
        }

        for (String reference : referencedClasses(bytes)) {
            if (isGameClass(reference)) {
                named.add(reference);
            }
        }
    }

    /** Every class a class file names, read by passing it through a remapper that changes nothing. */
    static Set<String> referencedClasses(byte[] bytes) {
        Set<String> out = new LinkedHashSet<>();
        org.objectweb.asm.commons.Remapper recording = new org.objectweb.asm.commons.Remapper() {
            @Override
            public String map(String internalName) {
                out.add(internalName);
                return internalName;
            }
        };

        try {
            new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), recording), ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            LOG.debug("could not read the classes a restored class names: {}", e.toString());
        }

        return out;
    }

    private Path cachedCopy(String release, String internalName) {
        return cache.resolve(release + "-v" + FORMAT).resolve(internalName + ".class");
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".part");
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.debug("could not cache {}: {}", file, e.toString());
        }
    }

    private Optional<Source> open(String release) {
        Optional<Path> jar = mappings.clientJar(release);
        Optional<MappingSet> names = jar.isPresent() ? mappings.obfuscatedToCurrent(release) : Optional.empty();

        if (jar.isEmpty() || names.isEmpty()) {
            LOG.info("Minecraft {}'s classes are not available, so classes it had cannot be added back", release);
            return Optional.empty();
        }

        try {
            return Optional.of(new Source(new ZipFile(jar.get().toFile()), names.get()));
        } catch (IOException e) {
            LOG.warn("could not open Minecraft {}'s jar {}: {}", release, jar.get(), e.toString());
            return Optional.empty();
        }
    }

    static boolean isGameClass(String internalName) {
        for (String prefix : GAME_PACKAGES) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    static boolean isClientClass(String internalName) {
        for (String prefix : CLIENT_PACKAGES) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    /** One old release's jar and the table into today's names. */
    static final class Source {
        private final ZipFile jar;
        private final MappingSet names;
        private final Map<String, String> obfuscatedByName = new HashMap<>();
        private final Map<String, List<String>> parents = new ConcurrentHashMap<>();

        Source(ZipFile jar, MappingSet names) {
            this.jar = jar;
            this.names = names;
            names.classes().forEach((obf, named) -> obfuscatedByName.putIfAbsent(named, obf));
        }

        synchronized byte[] restore(String internalName) {
            String obf = obfuscatedByName.get(internalName);

            if (obf == null) {
                return null;
            }

            byte[] original = read(obf);

            if (original == null) {
                return null;
            }

            ClassWriter writer = new ClassWriter(0);
            new ClassReader(original).accept(new ClassRemapper(writer, names.remapper(this::parentsOf)), 0);
            return writer.toByteArray();
        }

        private byte[] read(String obf) {
            ZipEntry entry = jar.getEntry(obf + ".class");

            if (entry == null) {
                return null;
            }

            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            } catch (IOException e) {
                return null;
            }
        }

        /** A class's superclass and interfaces, in the old jar's own names. */
        private List<String> parentsOf(String obf) {
            List<String> known = parents.get(obf);

            if (known != null) {
                return known;
            }

            byte[] bytes = read(obf);
            List<String> out = new ArrayList<>();

            if (bytes != null) {
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String name, String signature, String superName,
                            String[] interfaces) {
                        if (superName != null) {
                            out.add(superName);
                        }

                        if (interfaces != null) {
                            out.addAll(List.of(interfaces));
                        }
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }

            parents.put(obf, out);
            return out;
        }
    }
}
