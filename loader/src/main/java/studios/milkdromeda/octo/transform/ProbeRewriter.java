package studios.milkdromeda.octo.transform;

import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Sends a mod's own class lookups through {@link Probes}, so that asking
 * whether a class exists never finds a stand-in.
 */
public final class ProbeRewriter implements Transformer {
    private static final String PROBES = "studios/milkdromeda/octo/transform/Probes";

    private final ByteScan mentionsALookup = new ByteScan(List.of("forName", "loadClass"));

    @Override
    public String name() {
        return "probes";
    }

    /**
     * Everything but the game's own classes: a library a mod bundles probes
     * for its optional dependencies the same way, and what a mixin adds to a
     * game class was rewritten in the mixin before it got there.
     */
    @Override
    public boolean handles(String className, TransformContext context) {
        return !className.startsWith("net/minecraft/") && !className.startsWith("com/mojang/");
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        if (!mentionsALookup.matches(bytes)) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature,
                        exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String member, String desc,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals("java/lang/Class")
                                && member.equals("forName") && (desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")
                                        || desc.equals("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"))) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBES, member, desc, false);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/ClassLoader")
                                && member.equals("loadClass") && desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, PROBES, member,
                                    "(Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/Class;", false);
                        } else {
                            super.visitMethodInsn(opcode, owner, member, desc, isInterface);
                        }
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
