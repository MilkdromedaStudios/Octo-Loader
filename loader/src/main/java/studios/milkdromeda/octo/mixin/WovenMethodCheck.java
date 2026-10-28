package studios.milkdromeda.octo.mixin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Keeps one mixin method the JVM would refuse from taking its target class with it.
 *
 * <p>A mixin written for another version can be merged into a class and come
 * out unverifiable: Moonlight's spawn handler on {@code ChunkGenerator}
 * captured two locals the 26.x method no longer has, the handler was merged
 * without them, and its body still read them. The JVM refuses the whole class
 * at its first use. That class was {@code ChunkGenerator}, used by the game's
 * bootstrap, so the bootstrap stopped half way, and the server then failed
 * on a frozen registry with nothing pointing back at Moonlight.
 *
 * <p>So every method the weave added is checked, without loading any class,
 * for the faults that need no type hierarchy to see: a local read before it
 * was written, the wrong kind of value on the stack. One that fails keeps its
 * signature, so whatever calls it still links, and loses its body: it returns
 * at once, with a default when it returns anything. The class loads, and only
 * that mixin's effect is missing.
 */
final class WovenMethodCheck {
    private static final OctoLog LOG = OctoLog.of(WovenMethodCheck.class);
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

    private WovenMethodCheck() {
    }

    /**
     * @param before the class as it was handed to mixin
     * @param after  the class as mixin handed it back
     * @return {@code after}, with any added method that would not verify emptied
     */
    static byte[] check(String className, byte[] before, byte[] after) {
        if (after == null || after == before) {
            return after;
        }

        Set<String> original = new HashSet<>();

        try {
            ClassNode untouched = new ClassNode();
            new ClassReader(before).accept(untouched, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);

            for (MethodNode method : untouched.methods) {
                original.add(method.name + method.desc);
            }

            ClassNode woven = new ClassNode();
            new ClassReader(after).accept(woven, 0);
            boolean changed = false;

            for (MethodNode method : woven.methods) {
                if (original.contains(method.name + method.desc)
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    continue;
                }

                try {
                    new Analyzer<BasicValue>(new BasicVerifier()).analyze(woven.name, method);
                } catch (AnalyzerException | RuntimeException e) {
                    LOG.warn("{}: the mixin method {} from {} would not verify on this Minecraft, so it does "
                            + "nothing: {}", className.replace('/', '.'), method.name, mixinOf(method),
                            e.getMessage());
                    empty(method);
                    changed = true;
                }
            }

            if (!changed) {
                return after;
            }

            ClassWriter writer = new ClassWriter(0);
            woven.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            // The check is a safety net; a class it cannot read is left as mixin made it.
            LOG.debug("could not check the mixins woven into {}: {}", className, e.toString());
            return after;
        }
    }

    private static String mixinOf(MethodNode method) {
        for (List<AnnotationNode> annotations : java.util.Arrays.asList(method.invisibleAnnotations,
                method.visibleAnnotations)) {
            if (annotations == null) {
                continue;
            }

            for (AnnotationNode annotation : annotations) {
                if (MERGED.equals(annotation.desc) && annotation.values != null) {
                    for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                        if ("mixin".equals(annotation.values.get(i))) {
                            return String.valueOf(annotation.values.get(i + 1));
                        }
                    }
                }
            }
        }

        return "a mixin";
    }

    /** Replaces the body with an immediate return of the type's default. */
    private static void empty(MethodNode method) {
        Type returnType = Type.getReturnType(method.desc);
        InsnList body = new InsnList();

        switch (returnType.getSort()) {
            case Type.VOID -> body.add(new InsnNode(Opcodes.RETURN));
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                body.add(new InsnNode(Opcodes.ICONST_0));
                body.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.LONG -> {
                body.add(new InsnNode(Opcodes.LCONST_0));
                body.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                body.add(new InsnNode(Opcodes.FCONST_0));
                body.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                body.add(new InsnNode(Opcodes.DCONST_0));
                body.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                body.add(new InsnNode(Opcodes.ACONST_NULL));
                body.add(new InsnNode(Opcodes.ARETURN));
            }
        }

        method.instructions = body;
        method.tryCatchBlocks.clear();

        if (method.localVariables != null) {
            method.localVariables.clear();
        }

        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = 2;
        method.maxLocals = (Type.getArgumentsAndReturnSizes(method.desc) >> 2)
                - ((method.access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);
    }
}
