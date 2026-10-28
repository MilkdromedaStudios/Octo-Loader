package studios.milkdromeda.octo.transform;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Lets a mod from another version extend what the game has since closed.
 *
 * <p>Mojang seals and finalises types mods used to subclass. {@code Holder}
 * is a sealed interface in 26.x, permitting only its own two kinds, and
 * Moonlight's {@code RegSupplier} implements it, so Moonlight could not be
 * defined, and neither could Supplementaries nor anything else that needs it.
 * Authlib's {@code GameProfile} became a record, final, and Create's deployer
 * subclasses it for its fake player.
 *
 * <p>A sealed type loses its list of permitted subclasses; the JVM checks that
 * list only when a subclass is defined, so the game's own classes and its
 * pattern switches behave as before. A final class is opened only when a mod
 * extends it, since that costs a record its {@code isRecord()}.
 */
public final class OpenTypes implements Transformer {
    private static final OctoLog LOG = OctoLog.of(OpenTypes.class);
    private static final ByteScan SEALED = new ByteScan(List.of("PermittedSubclasses"));

    private final Set<String> extended;
    private final ByteScan extendedNames;

    /** @param extended the classes some mod's class extends, by internal name */
    public OpenTypes(Set<String> extended) {
        this.extended = Set.copyOf(extended);
        this.extendedNames = new ByteScan(extended);
    }

    @Override
    public String name() {
        return "open-types";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() == null && !className.startsWith("studios/milkdromeda/octo/");
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        boolean sealed = SEALED.matches(bytes);
        boolean subclassed = extended.contains(className) && extendedNames.matches(bytes);

        if (!sealed && !subclassed) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        boolean opensFinal = subclassed && (reader.getAccess() & Opcodes.ACC_FINAL) != 0;
        boolean[] changed = { opensFinal };
        ClassWriter writer = new ClassWriter(reader, 0);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
                super.visit(version, opensFinal ? access & ~Opcodes.ACC_FINAL : access, name, signature, superName,
                        interfaces);
            }

            @Override
            public void visitPermittedSubclass(String permittedSubclass) {
                changed[0] = true;
            }
        }, 0);

        if (!changed[0]) {
            return bytes;
        }

        if (opensFinal) {
            LOG.info("{} is final in this Minecraft and a mod extends it; it is opened", className.replace('/', '.'));
        }

        return writer.toByteArray();
    }
}
