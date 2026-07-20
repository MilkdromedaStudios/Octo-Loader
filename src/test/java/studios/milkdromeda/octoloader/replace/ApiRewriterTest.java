package studios.milkdromeda.octoloader.replace;

import org.junit.jupiter.api.Test;
import studios.milkdromeda.octoloader.TestClasses;
import studios.milkdromeda.octoloader.bytecode.ConstantPool;
import studios.milkdromeda.octoloader.knowledge.KnowledgeBase;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRewriterTest {
    @Test
    void rewritesOldClassAndMemberReferencesToTheirDocumentedNames() throws IOException {
        ReplacementPlan.Replace replace = assertInstanceOf(ReplacementPlan.Replace.class,
                ApiReplacement.plan("~26.1", "26.2", KnowledgeBase.load()));

        byte[] original = TestClasses.cls("fix/OldMod")
                .newInsn("net/neoforged/fml/event/lifecycle/FMLSetupEvent")
                .invokeInterface("net/neoforged/bus/api/IEventBus", "addEventListener",
                        "(Ljava/util/function/Consumer;)V")
                .getField("net/minecraft/world/level/Level", "dimensionId",
                        "Lnet/minecraft/util/Identifier;")
                .bytes();
        assertTrue(replace.touches(ConstantPool.scan(original)));

        byte[] rewritten = ApiRewriter.rewrite(original, replace.remapper());
        ConstantPool.ClassRefs refs = ConstantPool.scan(rewritten);

        assertTrue(refs.classes().contains("net/neoforged/fml/event/lifecycle/FMLCommonSetupEvent"));
        assertFalse(refs.classes().contains("net/neoforged/fml/event/lifecycle/FMLSetupEvent"));

        assertTrue(refs.members().stream().anyMatch(m -> m.name().equals("addListener")));
        assertFalse(refs.members().stream().anyMatch(m -> m.name().equals("addEventListener")));

        // Field rename applied, and the descriptor's Identifier moved packages too.
        assertTrue(refs.members().stream().anyMatch(m -> m.name().equals("dimension")
                && m.desc().equals("Lnet/minecraft/resources/Identifier;")));
    }

    @Test
    void untouchedClassesAreLeftAlone() throws IOException {
        ReplacementPlan.Replace replace = assertInstanceOf(ReplacementPlan.Replace.class,
                ApiReplacement.plan("~26.1", "26.2", KnowledgeBase.load()));
        byte[] plain = TestClasses.cls("fix/Plain")
                .invokeVirtual("java/lang/String", "trim", "()Ljava/lang/String;")
                .bytes();
        assertFalse(replace.touches(ConstantPool.scan(plain)));
    }
}
