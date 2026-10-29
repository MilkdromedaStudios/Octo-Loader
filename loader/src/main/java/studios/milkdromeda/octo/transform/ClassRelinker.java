package studios.milkdromeda.octo.transform;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Finds the class a mod from another version meant, when it moved.
 *
 * <p>Between the release a mod was built for and this one, classes move to
 * other packages and are renamed to other words far more often than they
 * disappear. Mojang moves a class into a new package; Fabric API, for 26.1,
 * renamed its own API to Mojang's vocabulary — {@code ClientWorldEvents} is
 * {@code ClientLevelEvents}, {@code KeyBindingHelper} is
 * {@code KeyMappingHelper}, the item group API is the creative tab API. No
 * table lists every one of those, but they follow from two rules, and this
 * applies them to a reference only when the original is genuinely absent:
 *
 * <ol>
 *   <li>the same simple name in another package, when exactly one class in
 *       the same family of packages has it;</li>
 *   <li>the same name in the newer vocabulary — Yarn's words for Mojang's —
 *       in the same package, a renamed one, or failing that wherever it is
 *       unique.</li>
 * </ol>
 *
 * <p>Only mods carried across from another version are touched, and every
 * substitution is logged.
 */
public final class ClassRelinker implements Transformer {
    private static final OctoLog LOG = OctoLog.of(ClassRelinker.class);

    /** Yarn's word, or an older word, and the word used now. Applied to names. */
    private static final String[][] WORDS = {
            { "ItemGroups", "CreativeModeTabs" }, { "ItemGroup", "CreativeModeTab" },
            { "ClientWorld", "ClientLevel" }, { "ServerWorld", "ServerLevel" }, { "World", "Level" },
            { "BlockView", "BlockGetter" }, { "KeyBinding", "KeyMapping" },
            { "PacketByteBuf", "FriendlyByteBuf" }, { "C2S", "Serverbound" }, { "S2C", "Clientbound" },
            { "FeatureRenderer", "RenderLayer" }, { "TooltipComponent", "ClientTooltipComponent" },
            { "SpriteProvider", "SpriteSet" }, { "ResourceLocation", "Identifier" },
            { "ScreenHandler", "Menu" } };

    /** Package segments renamed the same way. */
    private static final String[][] PACKAGES = {
            { "/itemgroup/", "/creativetab/" }, { "/blockview/", "/blockgetter/" },
            { "/keybinding/", "/keymapping/" }, { "/api/renderer/", "/api/client/renderer/" },
            { "/api/client/keybinding/", "/api/client/keymapping/" } };

    /** Families of packages a moved class is looked for within. */
    private static final List<String> FAMILIES = List.of("net/minecraft/", "com/mojang/", "net/fabricmc/fabric/");

    private final Predicate<String> exists;
    private final Map<String, List<String>> bySimpleName;
    private final Map<String, Optional<String>> decisions = new ConcurrentHashMap<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    ClassRelinker(Predicate<String> exists, Map<String, List<String>> bySimpleName) {
        this.exists = exists;
        this.bySimpleName = bySimpleName;
    }

    /**
     * Indexes every class on {@code path} by its simple name.
     *
     * <p>Zip directories only, never class bytes: a mods folder is tens of
     * thousands of classes, and their names are all this needs.
     */
    public static ClassRelinker over(List<URL> path, Predicate<String> exists) {
        Map<String, List<String>> bySimpleName = new HashMap<>();

        for (URL url : path) {
            Path jar;

            try {
                jar = Path.of(url.toURI());
            } catch (Exception e) {
                continue;
            }

            if (!Files.isRegularFile(jar)) {
                continue;
            }

            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();

                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();

                    if (!name.endsWith(".class") || name.startsWith("META-INF/") || !inFamily(name)) {
                        continue;
                    }

                    String internal = name.substring(0, name.length() - 6);
                    bySimpleName.computeIfAbsent(simpleName(internal), ignored -> new ArrayList<>(1)).add(internal);
                }
            } catch (IOException e) {
                LOG.debug("could not index {}: {}", jar, e.toString());
            }
        }

        return new ClassRelinker(exists, bySimpleName);
    }

    /** Every substitution made, for the launch summary. */
    public Set<String> relinked() {
        return new LinkedHashSet<>(reported);
    }

    @Override
    public String name() {
        return "class-relinker";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        boolean[] changed = { false };
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                String replacement = replacementFor(internalName);

                if (replacement != null) {
                    changed[0] = true;
                    context.note("relinked " + internalName.replace('/', '.') + " to " + replacement.replace('/', '.'));
                    return replacement;
                }

                return internalName;
            }
        };

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper), 0);
        return changed[0] ? writer.toByteArray() : bytes;
    }

    /** @return the class to use instead of {@code internalName}, or {@code null} to leave it */
    public String replacementFor(String internalName) {
        if (!inFamily(internalName)) {
            return null;
        }

        // Not computeIfAbsent: an inner class's search asks about its outer
        // class, and a map must not be updated from inside its own update.
        Optional<String> decided = decisions.get(internalName);

        // Decided once, decided for good. A name relinked for the classes
        // loaded first can be added back from the old release afterwards,
        // for a class nobody relinked; asked then whether it exists, the
        // answer was yes, and the classes loaded later named the old class
        // while the earlier ones named the new. A mod's own code ended up
        // holding both — Create's buffers asked the old model type for
        // methods only the new one is answered for.
        if (decided != null) {
            return decided.orElse(null);
        }

        if (exists.test(internalName)) {
            return null;
        }

        decided = search(internalName);
        decisions.put(internalName, decided);
        return decided.orElse(null);
    }

    private Optional<String> search(String missing) {
        int dollar = missing.indexOf('$');

        // An inner class moves with its outer one.
        if (dollar > 0) {
            String outer = missing.substring(0, dollar);
            String movedOuter = exists.test(outer) ? outer : replacementFor(outer);

            if (movedOuter != null) {
                for (String inner : variants(missing.substring(dollar))) {
                    if (exists.test(movedOuter + inner)) {
                        return found(missing, movedOuter + inner);
                    }
                }
            }

            return Optional.empty();
        }

        String packageName = missing.substring(0, missing.lastIndexOf('/') + 1);
        String simple = simpleName(missing);
        List<String> simpleVariants = variants(simple);

        for (String pkg : variants(packageName)) {
            for (String name : simpleVariants) {
                String candidate = pkg + name;

                if (!candidate.equals(missing) && exists.test(candidate)) {
                    return found(missing, candidate);
                }
            }
        }

        String family = familyOf(missing);

        for (String name : simpleVariants) {
            List<String> elsewhere = new ArrayList<>();

            for (String candidate : bySimpleName.getOrDefault(name, List.of())) {
                if (candidate.startsWith(family)) {
                    elsewhere.add(candidate);
                }
            }

            if (elsewhere.size() == 1) {
                return found(missing, elsewhere.get(0));
            }
        }

        return Optional.empty();
    }

    private Optional<String> found(String missing, String replacement) {
        if (reported.add(missing.replace('/', '.') + " -> " + replacement.replace('/', '.'))) {
            LOG.info("{} is not in this version; using {} instead", missing.replace('/', '.'),
                    replacement.replace('/', '.'));
        }

        return Optional.of(replacement);
    }

    /** A name as it is, then with each newer word in it. */
    private static List<String> variants(String name) {
        Set<String> out = new LinkedHashSet<>();
        out.add(name);
        String all = name;

        for (String[] pair : name.contains("/") ? PACKAGES : WORDS) {
            if (name.contains(pair[0])) {
                out.add(name.replace(pair[0], pair[1]));
                all = all.replace(pair[0], pair[1]);
            }
        }

        out.add(all);
        return new ArrayList<>(out);
    }

    private static boolean inFamily(String name) {
        return familyOf(name) != null;
    }

    private static String familyOf(String name) {
        for (String family : FAMILIES) {
            if (name.startsWith(family)) {
                return family;
            }
        }

        return null;
    }

    private static String simpleName(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }
}
