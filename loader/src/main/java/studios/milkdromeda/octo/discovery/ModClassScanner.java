package studios.milkdromeda.octo.discovery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.neoforged.fml.loading.modscan.ModAnnotation;

import studios.milkdromeda.octo.compat.Era.MappingNamespace;
import studios.milkdromeda.octo.mod.BytecodeIndex.ScannedAnnotation;
import studios.milkdromeda.octo.mod.BytecodeIndex.ScannedClass;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Reads a mod's bytecode to find what its metadata did not say.
 *
 * <p>Four jobs:
 * <ul>
 *   <li>Recover entrypoints. Forge and NeoForge never write them down — the
 *       {@code @Mod} annotation is the entrypoint. Mods from before {@code mcmod.info}
 *       have no metadata at all, only a {@code mod_Something} class.</li>
 *   <li>Work out which naming scheme the mod calls the game by, which decides
 *       what has to be remapped.</li>
 *   <li>Note the class file version, which bounds how old the mod can be.</li>
 *   <li>Index every class and annotation, which is how a Forge or NeoForge mod
 *       finds the classes it is meant to construct: they are never named
 *       anywhere, only annotated, and the loader is expected to know.</li>
 * </ul>
 */
public final class ModClassScanner {
    private static final OctoLog LOG = OctoLog.of(ModClassScanner.class);

    private static final Set<String> MOD_ANNOTATIONS = Set.of(
            "Lnet/minecraftforge/fml/common/Mod;",   // Forge 1.13+
            "Lnet/neoforged/fml/common/Mod;",        // NeoForge
            "Lcpw/mods/fml/common/Mod;",             // Forge 1.6.4 and earlier
            "Lcpw/mods/modlauncher/api/Mod;");

    private static final Set<String> FABRIC_INITIALIZERS = Set.of(
            "net/fabricmc/api/ModInitializer",
            "org/quiltmc/qsl/base/api/entrypoint/ModInitializer");

    private static final Set<String> CLIENT_INITIALIZERS = Set.of(
            "net/fabricmc/api/ClientModInitializer",
            "org/quiltmc/qsl/base/api/entrypoint/client/ClientModInitializer");

    private static final Set<String> SERVER_INITIALIZERS = Set.of(
            "net/fabricmc/api/DedicatedServerModInitializer",
            "org/quiltmc/qsl/base/api/entrypoint/server/DedicatedServerModInitializer");

    private static final Set<String> PRELAUNCH_INITIALIZERS = Set.of(
            "net/fabricmc/loader/api/entrypoint/PreLaunchEntrypoint",
            "org/quiltmc/loader/api/entrypoint/PreLaunchEntrypoint");

    private static final String LITEMOD = "com/mumfrey/liteloader/LiteMod";

    private static final Pattern SEARGE_MEMBER = Pattern.compile("(func|field)_\\d+_[a-zA-Z]+_?");
    private static final Pattern INTERMEDIARY_CLASS = Pattern.compile("net/minecraft/class_\\d+.*");
    private static final Pattern INTERMEDIARY_MEMBER = Pattern.compile("(method|field)_\\d+");
    private static final Pattern OBFUSCATED_CLASS = Pattern.compile("[a-z]{1,3}(\\$[a-z0-9]{1,3})?");

    public ScanResult scan(ModSource source) {
        ScanResult result = new ScanResult();

        for (String entry : source.entries()) {
            if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) {
                continue;
            }

            source.read(entry).ifPresent(bytes -> {
                try {
                    new ClassReader(bytes).accept(new Visitor(result), ClassReader.SKIP_FRAMES);
                } catch (RuntimeException e) {
                    // A malformed or newer-than-ASM class must not sink the whole scan.
                    LOG.debug("skipping unreadable class {} in {}: {}", entry, source.path(), e.toString());
                }
            });
        }

        return result;
    }

    private static final class Visitor extends ClassVisitor {
        private final ScanResult result;
        private String className;

        Visitor(ScanResult result) {
            super(Opcodes.ASM9);
            this.result = result;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name;
            result.countClass(version & 0xFFFF);
            result.classes().add(new ScannedClass(name, superName,
                    interfaces == null ? List.of() : List.of(interfaces)));

            if (interfaces != null) {
                for (String candidate : interfaces) {
                    classify(candidate, name);
                }
            }

            if (superName != null) {
                classify(superName, name);
                noteReference(superName);

                // ModLoader mods extended BaseMod and were named mod_Something.
                if (superName.equals("BaseMod") || superName.endsWith("/BaseMod")) {
                    result.modLoaderClasses().add(binary(name));
                }
            }

            if (name.startsWith("mod_") || name.contains("/mod_")) {
                result.modLoaderClasses().add(binary(name));
            }
        }

        private void classify(String interfaceName, String owner) {
            if (FABRIC_INITIALIZERS.contains(interfaceName)) {
                result.fabricInitializers().add(binary(owner));
            } else if (CLIENT_INITIALIZERS.contains(interfaceName)) {
                result.clientInitializers().add(binary(owner));
            } else if (SERVER_INITIALIZERS.contains(interfaceName)) {
                result.serverInitializers().add(binary(owner));
            } else if (PRELAUNCH_INITIALIZERS.contains(interfaceName)) {
                result.preLaunchInitializers().add(binary(owner));
            } else if (interfaceName.equals(LITEMOD) || interfaceName.startsWith(LITEMOD + "$")) {
                result.liteMods().add(binary(owner));
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return collect(descriptor, ScannedAnnotation.Target.TYPE, binary(className));
        }

        /**
         * Records an annotation, and notices when it is the one that declares a mod.
         *
         * <p>Everything a mod's own bytecode is annotated with is kept, not just
         * {@code @Mod}: this is the register a Forge or NeoForge mod reads to find
         * the classes it should instantiate, and it has to be complete before the
         * first mod is constructed, because that is when they ask.
         *
         * <p>Including the annotations the compiler marked invisible, which is what
         * Forge and NeoForge do. It looks like waste — nothing can reflect on an
         * annotation that is not retained at run time — but scanning reads bytecode,
         * not reflection, so a mod can perfectly well mark its plugins with a
         * class-retained annotation and find them here. Dropping those would work
         * for every mod that happens to have chosen {@code RUNTIME} and fail
         * silently, with an empty list, for any that did not.
         */
        private AnnotationVisitor collect(String descriptor, ScannedAnnotation.Target target, String member) {
            Map<String, Object> values = new LinkedHashMap<>(4);
            boolean declaresMod = MOD_ANNOTATIONS.contains(descriptor);
            String owner = binary(className);

            if (declaresMod) {
                result.modAnnotations().putIfAbsent(owner, "");
            }

            // Filed when the annotation ends rather than when it starts, so that a
            // marker annotation — which most of them are, over tens of thousands of
            // classes — does not each keep an empty map alive for the whole launch.
            return new Values(values, () -> {
                result.annotations().add(new ScannedAnnotation(descriptor, target, className, member,
                        values.isEmpty() ? Map.of() : values));

                // Forge used value(), then modid(); NeoForge went back to value().
                if (declaresMod) {
                    Object id = values.getOrDefault("value", values.get("modid"));

                    if (id instanceof String text && !text.isBlank()) {
                        result.modAnnotations().put(owner, text);
                    }
                }
            });
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            noteMember(name);
            noteType(descriptor);

            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    return collect(annotation, ScannedAnnotation.Target.FIELD, name);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            noteMember(name);
            noteType(descriptor);

            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    // Mods expect a method's member name to carry its descriptor:
                    // an annotation on one overload is not on the others.
                    return collect(annotation, ScannedAnnotation.Target.METHOD, name + descriptor);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    noteReference(owner);
                    noteMember(name);
                    noteType(descriptor);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    noteReference(owner);
                    noteMember(name);
                    noteType(descriptor);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    noteReference(type);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle handle, Object... args) {
                    noteType(descriptor);
                }
            };
        }

        private void noteType(String descriptor) {
            if (descriptor == null || descriptor.isEmpty()) {
                return;
            }

            try {
                if (descriptor.charAt(0) == '(') {
                    for (Type argument : Type.getArgumentTypes(descriptor)) {
                        noteTypeRef(argument);
                    }

                    noteTypeRef(Type.getReturnType(descriptor));
                } else {
                    noteTypeRef(Type.getType(descriptor));
                }
            } catch (RuntimeException ignored) {
                // Descriptors from very old compilers are occasionally malformed.
            }
        }

        private void noteTypeRef(Type type) {
            Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;

            if (element.getSort() == Type.OBJECT) {
                noteReference(element.getInternalName());
            }
        }

        private void noteReference(String internalName) {
            if (internalName == null || internalName.isEmpty() || internalName.charAt(0) == '[') {
                return;
            }

            if (internalName.startsWith("net/minecraft/")) {
                result.referencedGameClasses().add(internalName);

                if (INTERMEDIARY_CLASS.matcher(internalName).matches()) {
                    result.hit(MappingNamespace.INTERMEDIARY);
                } else {
                    // net/minecraft/client/Minecraft in a mod is either Mojang's
                    // own naming or MCP's; the member names below tell them apart.
                    result.hit(MappingNamespace.MOJANG);
                }
            } else if (OBFUSCATED_CLASS.matcher(internalName).matches()) {
                // A one-to-three letter class in the default package is Mojang's
                // obfuscation, which only pre-1.14 mods ever linked against directly.
                result.referencedGameClasses().add(internalName);
                result.hit(MappingNamespace.OFFICIAL);
            }
        }

        private void noteMember(String name) {
            if (name == null || name.length() < 6) {
                return;
            }

            if (SEARGE_MEMBER.matcher(name).matches()) {
                result.hit(MappingNamespace.SEARGE);
            } else if (INTERMEDIARY_MEMBER.matcher(name).matches()) {
                result.hit(MappingNamespace.INTERMEDIARY);
            }
        }

        private static String binary(String internalName) {
            return internalName.replace('/', '.');
        }
    }

    /**
     * Reads an annotation's members into the shape mods expect to find them in.
     *
     * <p>Strings, numbers, classes and booleans arrive as themselves; an array
     * becomes a list, a nested annotation becomes a map of its own members, and an
     * enum constant becomes an {@link ModAnnotation.EnumHolder}, which is the type
     * NeoForge puts in this position and therefore the type a mod casts to.
     */
    private static final class Values extends AnnotationVisitor {
        private final Map<String, Object> members;
        private final List<Object> items;
        private final Runnable onEnd;

        Values(Map<String, Object> members, Runnable onEnd) {
            super(Opcodes.ASM9);
            this.members = members;
            this.items = null;
            this.onEnd = onEnd;
        }

        private Values(List<Object> items) {
            super(Opcodes.ASM9);
            this.members = null;
            this.items = items;
            this.onEnd = null;
        }

        /** Inside an array every value arrives with a null name, in order. */
        private void put(String name, Object value) {
            if (items != null) {
                items.add(value);
            } else {
                members.put(name, value);
            }
        }

        @Override
        public void visit(String name, Object value) {
            put(name, value);
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            put(name, new ModAnnotation.EnumHolder(descriptor, value));
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            Map<String, Object> nested = new LinkedHashMap<>();
            put(name, nested);
            return new Values(nested, null);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            List<Object> values = new ArrayList<>();
            put(name, values);
            return new Values(values);
        }

        @Override
        public void visitEnd() {
            if (onEnd != null) {
                onEnd.run();
            }
        }
    }

    /** Convenience for tests and the CLI: scan a set of raw class files. */
    public ScanResult scanClasses(List<byte[]> classes) {
        ScanResult result = new ScanResult();

        for (byte[] bytes : classes) {
            new ClassReader(bytes).accept(new Visitor(result), ClassReader.SKIP_FRAMES);
        }

        return result;
    }
}
