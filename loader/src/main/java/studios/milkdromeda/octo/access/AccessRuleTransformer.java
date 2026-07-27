package studios.milkdromeda.octo.access;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import studios.milkdromeda.octo.transform.TransformContext;
import studios.milkdromeda.octo.transform.Transformer;

/**
 * Applies the merged access rules as classes are loaded.
 *
 * <p>This is what turns "{@code DebugScreenEntries.register} is private" and
 * "{@code Ingredient} is final" from a crash into a non-event: the mod was
 * compiled against a widened view of the game, so the game has to be widened to
 * match before the mod's first call reaches it.
 *
 * <p>Only access flags change. No frames, no stack maps and no method bodies are
 * touched, so the writer can copy the constant pool straight across and the
 * cost of running this over every class is close to nothing.
 */
public final class AccessRuleTransformer implements Transformer {
    private final AccessRules rules;

    public AccessRuleTransformer(AccessRules rules) {
        this.rules = rules;
    }

    @Override
    public String name() {
        return "access-widener";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return rules.touches(className);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        if (!rules.touches(className)) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new Widener(writer, className), 0);
        context.note("widened access on " + className.replace('/', '.'));
        return writer.toByteArray();
    }

    private final class Widener extends ClassVisitor {
        private final String className;
        private boolean isInterface;

        Widener(ClassVisitor delegate, String className) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            super.visit(version, rules.forClass(className).applyTo(access), name, signature, superName,
                    withInjectedInterfaces(interfaces));
        }

        /** Adds the interfaces a class tweaker declared, keeping the ones already there. */
        private String[] withInjectedInterfaces(String[] declared) {
            Set<String> injected = rules.interfacesFor(className);

            if (injected.isEmpty()) {
                return declared;
            }

            Set<String> all = new LinkedHashSet<>();

            if (declared != null) {
                all.addAll(Arrays.asList(declared));
            }

            all.addAll(injected);
            return all.toArray(new String[0]);
        }

        /**
         * Inner classes carry a second copy of their own access flags, and the
         * JVM checks that copy when the class is nested. Widening only the first
         * leaves a nested class that still cannot be reached.
         */
        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            super.visitInnerClass(name, outerName, innerName,
                    rules.touches(name) ? rules.forClass(name).applyTo(access) : access);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            Access rule = rules.forMethod(className, name, descriptor);
            return super.visitMethod(rule.applyToMethod(access, name, isInterface), name, descriptor, signature,
                    exceptions);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            Access rule = rules.forField(className, name, descriptor);
            return super.visitField(rule.applyTo(access), name, descriptor, signature, value);
        }
    }
}
