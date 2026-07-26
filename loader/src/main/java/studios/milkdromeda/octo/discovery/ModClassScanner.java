package studios.milkdromeda.octo.discovery;

import java.util.List;
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

import studios.milkdromeda.octo.compat.Era.MappingNamespace;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Reads a mod's bytecode to find what its metadata did not say.
 *
 * <p>Three jobs:
 * <ul>
 *   <li>Recover entrypoints. Forge and NeoForge never write them down — the
 *       {@code @Mod} annotation is the entrypoint. Mods from before {@code mcmod.info}
 *       have no metadata at all, only a {@code mod_Something} class.</li>
 *   <li>Work out which naming scheme the mod calls the game by, which decides
 *       what has to be remapped.</li>
 *   <li>Note the class file version, which bounds how old the mod can be.</li>
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
            if (!MOD_ANNOTATIONS.contains(descriptor)) {
                return null;
            }

            String owner = binary(className);
            result.modAnnotations().putIfAbsent(owner, "");

            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    // Forge used value(), then modid(); NeoForge went back to value().
                    if (value instanceof String text && !text.isBlank()
                            && (name.equals("value") || name.equals("modid"))) {
                        result.modAnnotations().put(owner, text);
                    }
                }
            };
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            noteMember(name);
            noteType(descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            noteMember(name);
            noteType(descriptor);

            return new MethodVisitor(Opcodes.ASM9) {
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

    /** Convenience for tests and the CLI: scan a set of raw class files. */
    public ScanResult scanClasses(List<byte[]> classes) {
        ScanResult result = new ScanResult();

        for (byte[] bytes : classes) {
            new ClassReader(bytes).accept(new Visitor(result), ClassReader.SKIP_FRAMES);
        }

        return result;
    }
}
