package studios.milkdromeda.octo.transform;

import java.util.LinkedHashSet;
import java.util.Set;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Keeps a translated mod's block, block entity or entity from stopping the
 * server the first time one of its game callbacks fails.
 *
 * <p>The game calls these on every tick of every one placed: Create's water
 * wheel works out its flow in {@code Block.tick}, its kinetic blocks tick
 * through a {@code BlockEntityTicker}. A member this version no longer has
 * answers a default there, the default is null one call later, and what the
 * game does with an exception from a tick is end the server with a crash
 * report. So each method a translated mod's class has that overrides one of
 * the game's and returns nothing is guarded, as a callback is
 * ({@link CallbackGuard}): what it throws is reported once and the call just
 * returns, which for a tick is the block standing still that tick.
 *
 * <p>Methods that answer something are left as written: the game goes on to
 * use the answer, and no default stands in for a shape or a result.
 */
public final class OverrideGuard implements Transformer {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private final MemberIndex index;

    public OverrideGuard(MemberIndex index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "override-guard";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);

        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0 || reader.readUnsignedShort(6) < Opcodes.V1_7) {
            return bytes;
        }

        Set<String> candidates = new LinkedHashSet<>();
        boolean[] isMixin = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                isMixin[0] |= MIXIN.equals(descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT
                        | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0
                        && !name.startsWith("<") && Type.getReturnType(descriptor) == Type.VOID_TYPE) {
                    candidates.add(name + descriptor);
                }

                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // A mixin's methods become the game's own; CallbackGuard decides for those.
        if (isMixin[0] || candidates.isEmpty()) {
            return bytes;
        }

        // Asked of the supertypes, not the class: it is being read right now.
        java.util.List<String> supertypes = new java.util.ArrayList<>(java.util.List.of(reader.getInterfaces()));
        supertypes.add(0, reader.getSuperName());
        Set<String> overrides = new LinkedHashSet<>();

        for (String method : candidates) {
            int split = method.indexOf('(');
            String name = method.substring(0, split);
            String descriptor = method.substring(split);

            if (supertypes.stream().anyMatch(type -> type != null
                    && index.isDeclaredFrom(type, name, descriptor, OverrideGuard::isGame))) {
                overrides.add(method);
            }
        }

        return overrides.isEmpty() ? bytes : CallbackGuard.guardMethods(bytes, className, overrides);
    }

    private static boolean isGame(String type) {
        return type.startsWith("net/minecraft/") || type.startsWith("com/mojang/");
    }
}
