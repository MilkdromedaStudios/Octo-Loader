package studios.milkdromeda.octo.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.access.AccessRules;
import studios.milkdromeda.octo.access.AccessTransformerReader;
import studios.milkdromeda.octo.access.InjectedInterface;
import studios.milkdromeda.octo.transform.TransformContext;
import studios.milkdromeda.octo.transform.Transformer;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Gives the game the shape the real NeoForge and Forge API jars expect of it.
 *
 * <p>Those jars are written against a game their installer patched: on NeoForge,
 * {@code Entity} is an {@code AttachmentHolder}, {@code MappedRegistry} is a
 * {@code BaseMappedRegistry}, {@code ItemStack} implements
 * {@code IItemStackExtension}, and the API's own code makes private game
 * members public through access transformers and reaches others through its
 * own accessor mixins. Run against the unpatched game, the first of those is a
 * {@code VerifyError} the moment a mod builds a registry, and the rest are
 * calls that link to nothing.
 *
 * <p>Most of what the patches did is published in the API jar itself:
 * {@code META-INF/injected-interfaces.json}, the access transformers its
 * {@code mods.toml} lists, and its mixin config. All three are applied here
 * exactly as they would be for a mod. What the binary patches alone carry — the
 * few game classes rebased onto an API superclass — is a short table, applied
 * only where the game class still extends {@code Object} and the API jar really
 * has the superclass.
 *
 * <p>This is the API's shape, not its behaviour: an {@code Entity} that is an
 * {@code AttachmentHolder} links, but nothing makes it save its attachments the
 * way patched NeoForge does.
 */
public final class LoaderApiPatches {
    private static final OctoLog LOG = OctoLog.of(LoaderApiPatches.class);

    /**
     * The superclasses NeoForge's binary patches give game classes, which no
     * data file in the API jar lists. Found by verifying the API's own code
     * against the unpatched game: these are the classes it needs to be
     * subclasses of something of its own.
     */
    private static final Map<String, String> SUPERCLASSES = Map.of(
            "net/minecraft/core/MappedRegistry", "net/neoforged/neoforge/registries/BaseMappedRegistry",
            "net/minecraft/world/entity/Entity", "net/neoforged/neoforge/attachment/AttachmentHolder",
            "net/minecraft/world/level/block/entity/BlockEntity", "net/neoforged/neoforge/attachment/AttachmentHolder",
            "net/minecraft/world/level/Level", "net/neoforged/neoforge/attachment/AttachmentHolder");

    private final AccessRules accessRules;
    private final Map<String, String> superclasses;
    private final List<String> mixinConfigs;

    private LoaderApiPatches(AccessRules accessRules, Map<String, String> superclasses, List<String> mixinConfigs) {
        this.accessRules = accessRules;
        this.superclasses = Map.copyOf(superclasses);
        this.mixinConfigs = List.copyOf(mixinConfigs);
    }

    public static final LoaderApiPatches NONE = new LoaderApiPatches(AccessRules.EMPTY, Map.of(), List.of());

    /** Reads what each API jar publishes about the game it expects. */
    public static LoaderApiPatches read(List<Path> jars) {
        if (jars.isEmpty()) {
            return NONE;
        }

        AccessRules.Builder access = AccessRules.builder();
        Map<String, String> superclasses = new LinkedHashMap<>();
        List<String> mixinConfigs = new ArrayList<>();

        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                String origin = jar.getFileName().toString();
                access.merge(injectedInterfaces(zip, origin));
                Metadata metadata = metadata(zip);

                for (String file : metadata.accessTransformers()) {
                    byte[] bytes = read(zip, file);

                    if (bytes != null) {
                        access.merge(AccessTransformerReader.read(bytes, origin + "!" + file));
                    }
                }

                for (String config : metadata.mixinConfigs()) {
                    if (zip.getEntry(config) != null) {
                        mixinConfigs.add(config);
                    }
                }

                SUPERCLASSES.forEach((target, superclass) -> {
                    if (zip.getEntry(superclass + ".class") != null) {
                        superclasses.put(target, superclass);
                    }
                });
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not read what {} expects of the game: {}", jar.getFileName(), e.toString());
            }
        }

        LoaderApiPatches patches = new LoaderApiPatches(access.build(), superclasses, mixinConfigs);
        LOG.info("shaping the game for the loader API: {}, {} superclass(es), {} mixin config(s)",
                patches.accessRules, superclasses.size(), mixinConfigs.size());
        return patches;
    }

    /** The API's access transformers and injected interfaces, applied with the mods' own. */
    public AccessRules accessRules() {
        return accessRules;
    }

    /** The API's own mixin configs, registered with the mods'. */
    public List<String> mixinConfigs() {
        return mixinConfigs;
    }

    /** @return the rewrite that rebases game classes onto API superclasses, or {@code null} when none apply */
    public Transformer rebaser() {
        return superclasses.isEmpty() ? null : new Rebaser(superclasses);
    }

    // ----------------------------------------------------------------- reading

    /**
     * {@code META-INF/injected-interfaces.json}: game class to the interfaces
     * it gains. NeoForge writes generics the way Java source does —
     * {@code IHolderExtension<T>} — where a class signature wants
     * {@code IHolderExtension<TT;>}.
     */
    private static AccessRules injectedInterfaces(ZipFile zip, String origin) {
        byte[] bytes = read(zip, "META-INF/injected-interfaces.json");

        if (bytes == null) {
            return AccessRules.EMPTY;
        }

        AccessRules.Builder rules = AccessRules.builder();
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            for (JsonElement declared : entry.getValue().getAsJsonArray()) {
                try {
                    rules.injectInterface(entry.getKey(), InjectedInterface.parse(signatureForm(declared.getAsString())));
                } catch (IllegalArgumentException e) {
                    LOG.debug("{}: skipping injected interface {}: {}", origin, declared, e.getMessage());
                }
            }
        }

        return rules.build();
    }

    /** {@code Name<T, U>} as {@code Name<TT;TU;>}; a class argument as {@code Lpkg/Name;}. */
    static String signatureForm(String declared) {
        int open = declared.indexOf('<');

        if (open < 0 || !declared.endsWith(">")) {
            return declared;
        }

        StringBuilder out = new StringBuilder(declared.substring(0, open)).append('<');

        for (String argument : declared.substring(open + 1, declared.length() - 1).split(",")) {
            String trimmed = argument.trim();

            if (trimmed.endsWith(";")) {
                out.append(trimmed);
            } else if (trimmed.contains("/") || trimmed.contains(".")) {
                out.append('L').append(trimmed.replace('.', '/')).append(';');
            } else {
                out.append('T').append(trimmed).append(';');
            }
        }

        return out.append('>').toString();
    }

    private record Metadata(List<String> accessTransformers, List<String> mixinConfigs) {
    }

    /**
     * The {@code [[accessTransformers]]} and {@code [[mixins]]} entries of the
     * jar's {@code mods.toml}. A full TOML reader is not needed for two keys in
     * two kinds of table.
     */
    private static Metadata metadata(ZipFile zip) {
        byte[] bytes = read(zip, "META-INF/neoforge.mods.toml");

        if (bytes == null) {
            bytes = read(zip, "META-INF/mods.toml");
        }

        List<String> transformers = new ArrayList<>();
        List<String> mixins = new ArrayList<>();

        if (bytes == null) {
            transformers.add("META-INF/accesstransformer.cfg");
            return new Metadata(transformers, mixins);
        }

        String table = "";

        for (String raw : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            String line = raw.trim();

            if (line.startsWith("[")) {
                table = line;
                continue;
            }

            int equals = line.indexOf('=');

            if (line.startsWith("#") || equals < 0) {
                continue;
            }

            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();

            if (value.length() < 2 || value.charAt(0) != '"') {
                continue;
            }

            value = value.substring(1, value.indexOf('"', 1));

            if (table.equals("[[accessTransformers]]") && key.equals("file")) {
                transformers.add(value);
            } else if (table.equals("[[mixins]]") && key.equals("config")) {
                mixins.add(value);
            }
        }

        if (transformers.isEmpty()) {
            transformers.add("META-INF/accesstransformer.cfg");
        }

        return new Metadata(transformers, mixins);
    }

    private static byte[] read(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);

        if (entry == null) {
            return null;
        }

        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- rebasing

    /**
     * Makes a game class extend the API superclass the patched game gives it.
     *
     * <p>Only a class that still extends {@code Object} is touched, and only
     * onto a superclass that itself extends {@code Object} with a no-argument
     * constructor — which is what both NeoForge's are. The constructor's
     * {@code super()} call is pointed at the new superclass; nothing else in
     * the class changes.
     */
    static final class Rebaser implements Transformer {
        private final Map<String, String> superclasses;

        Rebaser(Map<String, String> superclasses) {
            this.superclasses = superclasses;
        }

        @Override
        public String name() {
            return "loader-api-superclasses";
        }

        @Override
        public boolean handles(String className, TransformContext context) {
            return superclasses.containsKey(className);
        }

        @Override
        public byte[] transform(String className, byte[] bytes, TransformContext context) {
            String superclass = superclasses.get(className);
            ClassReader reader = new ClassReader(bytes);

            if (superclass == null || !"java/lang/Object".equals(reader.getSuperName())) {
                return bytes;
            }

            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName,
                        String[] interfaces) {
                    super.visit(version, access, name, rebasedSignature(signature, superclass), superclass,
                            interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return delegate == null || !name.equals("<init>") ? delegate
                            : new SuperCall(delegate, superclass);
                }
            }, 0);

            LOG.debug("{} now extends {}, as the loader API expects", className.replace('/', '.'),
                    superclass.replace('/', '.'));
            return writer.toByteArray();
        }

        /** A generic class's signature names its superclass first; that name changes too. */
        private static String rebasedSignature(String signature, String superclass) {
            if (signature == null) {
                return null;
            }

            int formalsEnd = signature.startsWith("<") ? closingBracket(signature) + 1 : 0;

            if (formalsEnd < 0 || !signature.startsWith("Ljava/lang/Object;", formalsEnd)) {
                return signature;
            }

            // The API superclasses are generic in the class's first type
            // variable, when it has one — BaseMappedRegistry<T> under
            // MappedRegistry<T> — and plain otherwise.
            String firstVariable = formalsEnd > 0 ? signature.substring(1, signature.indexOf(':')) : null;
            String rebased = superclass.endsWith("BaseMappedRegistry") && firstVariable != null
                    ? "L" + superclass + "<T" + firstVariable + ";>;"
                    : "L" + superclass + ";";
            return signature.substring(0, formalsEnd) + rebased
                    + signature.substring(formalsEnd + "Ljava/lang/Object;".length());
        }

        private static int closingBracket(String signature) {
            int depth = 0;

            for (int i = 0; i < signature.length(); i++) {
                char c = signature.charAt(i);

                if (c == '<') {
                    depth++;
                } else if (c == '>' && --depth == 0) {
                    return i;
                }
            }

            return -1;
        }
    }

    /**
     * Points a constructor's {@code super()} at the new superclass. A
     * {@code new Object()} elsewhere in the constructor is left alone, which is
     * why objects made with {@code NEW} are counted.
     */
    private static final class SuperCall extends MethodVisitor {
        private final String superclass;
        private int pendingObjects;

        SuperCall(MethodVisitor delegate, String superclass) {
            super(Opcodes.ASM9, delegate);
            this.superclass = superclass;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW && type.equals("java/lang/Object")) {
                pendingObjects++;
            }

            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESPECIAL && owner.equals("java/lang/Object") && name.equals("<init>")) {
                if (pendingObjects > 0) {
                    pendingObjects--;
                } else {
                    super.visitMethodInsn(opcode, superclass, name, descriptor, false);
                    return;
                }
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
