package studios.milkdromeda.octo.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import studios.milkdromeda.octo.compat.api.Equivalents;
import studios.milkdromeda.octo.testkit.SourceCompiler;
import studios.milkdromeda.octo.transform.TransformContext;

/**
 * A mixin written against one Minecraft, applied to another.
 *
 * <p>The target class is assembled rather than compiled, because the whole point
 * is the names it carries: a lambda the compiler numbered differently, a field
 * that was renamed, a method that is simply not there any more. Those are facts
 * about the class file, so the fixture states them directly.
 *
 * <p>The mixin itself is compiled from source with real mixin annotations, since
 * what is under test is whether those annotations come out the other side aimed
 * somewhere useful.
 */
class MixinTargetsTest {
    private static final String TARGET = "game/Screen";
    private static final String MIXIN = "mixinpkg/ScreenMixin";

    private static Map<String, byte[]> compiled;

    @BeforeAll
    static void compileTheMixin() {
        compiled = SourceCompiler.compile(Map.of("mixinpkg.ScreenMixin", """
                package mixinpkg;

                import java.util.Map;

                import org.spongepowered.asm.mixin.Mixin;
                import org.spongepowered.asm.mixin.gen.Accessor;
                import org.spongepowered.asm.mixin.injection.At;
                import org.spongepowered.asm.mixin.injection.Inject;
                import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

                @Mixin(targets = "game.Screen")
                public abstract class ScreenMixin {
                    @Inject(method = "lambda$reloadResourcePacks$21", at = @At("HEAD"))
                    private void onReload(CallbackInfo callback) {
                    }

                    @Inject(method = "shouldTriggerItemUseEffects", at = @At("HEAD"))
                    private void onUseEffects(CallbackInfo callback) {
                    }

                    @Inject(method = "removedEntirely", at = @At("HEAD"))
                    private void onRemoved(CallbackInfo callback) {
                    }

                    @Inject(method = "tick", at = @At("HEAD"))
                    private void onTick(CallbackInfo callback) {
                    }

                    @Accessor("entries")
                    public abstract Map<String, String> getEntries();
                }
                """));
    }

    /**
     * The target as this version of the game actually has it: the lambda carries
     * a different index, the detail map is spelled differently, and the method
     * the mod wanted has been renamed.
     */
    private static byte[] target() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "details", "Ljava/util/Map;", null, null).visitEnd();

        for (String name : List.of("<init>", "tick", "lambda$reloadResourcePacks$7", "isUsingItem")) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
            method.visitCode();

            if (name.equals("<init>")) {
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            }

            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(1, 1);
            method.visitEnd();
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final String TABLE = """
            {
              "methods": [
                { "owner": "game/Screen", "name": "shouldTriggerItemUseEffects",
                  "alternatives": ["isUsingItem"] }
              ]
            }
            """;

    private static byte[] adapt(byte[] mixin, Map<String, byte[]> classPath) {
        Equivalents table = Equivalents.parse(new StringReader(TABLE), "test");
        return new MixinTargets(table, classPath::get)
                .transform(MIXIN, mixin, TransformContext.of(null, classPath::containsKey));
    }

    private static ClassNode adapted() {
        Map<String, byte[]> classPath = new LinkedHashMap<>();
        classPath.put(TARGET, target());

        ClassNode node = new ClassNode();
        new ClassReader(adapt(compiled.get("mixinpkg.ScreenMixin"), classPath)).accept(node, 0);
        return node;
    }

    private static AnnotationNode injector(ClassNode mixin, String methodName) {
        for (MethodNode method : mixin.methods) {
            if (!method.name.equals(methodName)) {
                continue;
            }

            for (AnnotationNode annotation : all(method)) {
                if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
                        || annotation.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                    return annotation;
                }
            }
        }

        throw new AssertionError("no injector on " + methodName);
    }

    private static List<AnnotationNode> all(MethodNode method) {
        List<AnnotationNode> out = new java.util.ArrayList<>();

        if (method.visibleAnnotations != null) {
            out.addAll(method.visibleAnnotations);
        }

        if (method.invisibleAnnotations != null) {
            out.addAll(method.invisibleAnnotations);
        }

        return out;
    }

    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return null;
        }

        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }

        return null;
    }

    @Test
    @DisplayName("a lambda target is matched by the method it was written in, not by the compiler's index")
    void lambdaIsMatchedByItsEnclosingMethod() {
        assertEquals(List.of("lambda$reloadResourcePacks$7"),
                value(injector(adapted(), "onReload"), "method"),
                "the index moves between versions; the enclosing method name does not");
    }

    @Test
    @DisplayName("a renamed target is re-aimed through the compatibility table")
    void renamedTargetIsResolved() {
        assertEquals(List.of("isUsingItem"), value(injector(adapted(), "onUseEffects"), "method"));
    }

    @Test
    @DisplayName("a target that is genuinely gone is skipped rather than failing the launch")
    void vanishedTargetBecomesOptional() {
        AnnotationNode removed = injector(adapted(), "onRemoved");

        assertEquals(0, value(removed, "require"),
                "mixin's answer to an unmatched injector is to abort; Octo's is to lose the injection");
        assertEquals(0, value(removed, "expect"));
        assertEquals(List.of("removedEntirely"), value(removed, "method"),
                "the name is left as the mod wrote it, so the log still says what it wanted");
    }

    @Test
    @DisplayName("a target that is there is left exactly as the mod wrote it")
    void matchingTargetIsUntouched() {
        AnnotationNode tick = injector(adapted(), "onTick");

        assertEquals(List.of("tick"), value(tick, "method"));
        assertNull(value(tick, "require"), "a working injector must not be quietly made optional");
    }

    @Test
    @DisplayName("an accessor finds the field by its type when the name has changed")
    void accessorFindsTheFieldByItsType() {
        assertEquals("details", value(injector(adapted(), "getEntries"), "value"),
                "one field of that exact type on the class leaves no room for a wrong guess");
    }

    @Test
    @DisplayName("a mixin whose every target resolves comes back byte for byte unchanged")
    void nothingToDoChangesNothing() {
        Map<String, byte[]> classPath = new LinkedHashMap<>();
        classPath.put(TARGET, everythingPresent());

        byte[] original = compiled.get("mixinpkg.ScreenMixin");

        assertSame(original, adapt(original, classPath));
    }

    /** The same target, on a version that happens to have every name the mixin asks for. */
    private static byte[] everythingPresent() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "entries", "Ljava/util/Map;", null, null).visitEnd();

        for (String name : List.of("tick", "lambda$reloadResourcePacks$21", "shouldTriggerItemUseEffects",
                "removedEntirely")) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
            method.visitCode();
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(1, 1);
            method.visitEnd();
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("a mixin whose target is not on the class path is left for mixin to complain about")
    void unknownTargetIsLeftAlone() {
        byte[] original = compiled.get("mixinpkg.ScreenMixin");

        assertNotNull(original);
        assertSame(original, adapt(original, Map.of()));
    }
}
