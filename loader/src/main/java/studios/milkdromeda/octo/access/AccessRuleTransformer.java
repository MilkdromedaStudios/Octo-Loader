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
            int updated = rules.forClass(className).applyTo(access);
            Set<InjectedInterface> injected = rules.interfacesFor(className);

            if (injected.isEmpty()) {
                super.visit(version, updated, name, signature, superName, interfaces);
                return;
            }

            String[] declared = interfaces == null ? new String[0] : interfaces;
            Set<String> all = new LinkedHashSet<>(Arrays.asList(declared));
            StringBuilder generic = genericSignature(signature, superName, declared, injected);

            for (InjectedInterface added : injected) {
                // Only extend the signature for an interface actually added; a
                // duplicate would leave the signature describing more interfaces
                // than the class has.
                if (all.add(added.rawName()) && generic != null) {
                    generic.append(added.signature());
                }
            }

            super.visit(version, updated, name, generic == null ? signature : generic.toString(), superName,
                    all.toArray(new String[0]));
        }

        /**
         * The class signature to extend, or {@code null} when none is needed.
         *
         * <p>A class with no generics anywhere has no {@code Signature} attribute
         * and needs none. Adding a generic interface to such a class means
         * writing the signature it never had, from its superclass and the
         * interfaces it already declares.
         */
        private StringBuilder genericSignature(String signature, String superName, String[] declared,
                Set<InjectedInterface> injected) {
            if (signature != null) {
                return new StringBuilder(signature);
            }

            if (injected.stream().noneMatch(InjectedInterface::hasGenerics)) {
                return null;
            }

            StringBuilder built = new StringBuilder();
            built.append('L').append(superName == null ? "java/lang/Object" : superName).append(';');

            for (String existing : declared) {
                built.append('L').append(existing).append(';');
            }

            return built;
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
