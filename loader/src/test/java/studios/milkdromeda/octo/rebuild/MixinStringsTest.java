package studios.milkdromeda.octo.rebuild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import studios.milkdromeda.octo.compat.mapping.MappingSet;
import studios.milkdromeda.octo.testkit.SourceCompiler;

/**
 * A NeoForge mixin, which names the game in Mojang's names with no refmap,
 * rebuilt for a release that renamed what it names.
 */
class MixinStringsTest {
    private static final String FURNACE = "game/Furnace";
    private static Map<String, byte[]> compiled;

    @BeforeAll
    static void compile() {
        compiled = SourceCompiler.compile(Map.of("mixinpkg.FurnaceMixin", """
                package mixinpkg;

                import org.spongepowered.asm.mixin.Mixin;
                import org.spongepowered.asm.mixin.gen.Accessor;
                import org.spongepowered.asm.mixin.gen.Invoker;
                import org.spongepowered.asm.mixin.injection.At;
                import org.spongepowered.asm.mixin.injection.Inject;
                import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

                @Mixin(targets = "game.Furnace")
                public abstract class FurnaceMixin {
                    @Accessor
                    public abstract int getCookingProgress();

                    @Accessor("litTime")
                    public abstract int litTimeLeft();

                    @Invoker
                    public abstract void callBurnOld(int amount);

                    @Inject(method = "serverTickOld", at = @At(value = "INVOKE",
                            target = "Lgame/Furnace;burnOld(I)V"))
                    private void onTick(CallbackInfo callback) {
                    }

                    @Inject(method = "unchanged", at = @At("HEAD"))
                    private void onUnchanged(CallbackInfo callback) {
                    }
                }
                """));
    }

    /** What the release in between renamed. */
    private static Remapper renames() {
        return MappingSet.builder("test")
                .mapField(FURNACE, "cookingProgress", "I", "cookingTimer")
                .mapField(FURNACE, "litTime", "I", "litTimeRemaining")
                .mapMethod(FURNACE, "burnOld", "(I)V", "burn")
                .mapMethod(FURNACE, "serverTickOld", "()V", "serverTick")
                .build().remapper();
    }

    private static Map<String, Object> values(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        Map<String, Object> out = new java.util.LinkedHashMap<>();

        for (MethodNode method : node.methods) {
            List<AnnotationNode> annotations = new ArrayList<>();

            if (method.visibleAnnotations != null) {
                annotations.addAll(method.visibleAnnotations);
            }

            if (method.invisibleAnnotations != null) {
                annotations.addAll(method.invisibleAnnotations);
            }

            for (AnnotationNode annotation : annotations) {
                collect(method.name, annotation, out);
            }
        }

        return out;
    }

    private static void collect(String prefix, AnnotationNode annotation, Map<String, Object> out) {
        if (annotation.values == null) {
            return;
        }

        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            String key = prefix + "." + annotation.values.get(i);
            Object value = annotation.values.get(i + 1);

            if (value instanceof AnnotationNode nested) {
                collect(key, nested, out);
            } else if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof AnnotationNode) {
                list.forEach(item -> collect(key, (AnnotationNode) item, out));
            } else {
                out.put(key, value);
            }
        }
    }

    @Test
    @DisplayName("accessor, invoker and injector names follow the renames")
    void namesFollowTheRenames() {
        Map<String, Object> values = values(MixinStrings.translate(compiled.get("mixinpkg.FurnaceMixin"), renames(),
                List.of(FURNACE)));

        assertEquals("cookingTimer", values.get("getCookingProgress.value"),
                "an accessor naming its field only through its own name still has to find it");
        assertEquals("litTimeRemaining", values.get("litTimeLeft.value"));
        assertEquals("burn", values.get("callBurnOld.value"));
        assertEquals(List.of("serverTick"), values.get("onTick.method"));
        assertEquals("Lgame/Furnace;burn(I)V", values.get("onTick.at.target"));
        assertEquals(List.of("unchanged"), values.get("onUnchanged.method"));
    }

    @Test
    @DisplayName("with nothing renamed, the class comes back untouched")
    void nothingRenamedChangesNothing() {
        byte[] original = compiled.get("mixinpkg.FurnaceMixin");

        assertSame(original, MixinStrings.translate(original, MappingSet.EMPTY.remapper(), List.of(FURNACE)));
    }

    @Test
    @DisplayName("names are inferred the way mixin infers them")
    void inferenceMatchesMixin() {
        assertEquals("cookingProgress", MixinStrings.inferredField("getCookingProgress"));
        assertEquals("lit", MixinStrings.inferredField("isLit"));
        assertEquals("MAX_VALUE", MixinStrings.inferredField("getMAX_VALUE"));
        assertEquals("tick", MixinStrings.inferredMethod("invokeTick"));
    }
}
