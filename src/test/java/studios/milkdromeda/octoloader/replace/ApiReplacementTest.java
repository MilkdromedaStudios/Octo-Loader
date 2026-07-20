package studios.milkdromeda.octoloader.replace;

import org.junit.jupiter.api.Test;
import studios.milkdromeda.octoloader.bytecode.ConstantPool;
import studios.milkdromeda.octoloader.knowledge.KnowledgeBase;
import studios.milkdromeda.octoloader.knowledge.McVersionIndex;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiReplacementTest {
    private final KnowledgeBase kb = KnowledgeBase.load();

    // ------------------------------------------------------------- the timeline

    @Test
    void timelineReachesBackToBeta173AndEveryStepIsDocumented() {
        McVersionIndex index = kb.versionIndex();
        assertEquals("b1.7.3", index.oldest());
        assertEquals("26.2", index.newest());
        for (String id : index.ids()) {
            if (id.equals(index.newest())) continue;
            assertTrue(kb.apiDocs().stepFrom(id).isPresent(), "missing API doc for step from " + id);
        }
    }

    @Test
    void familyAliasesResolveToCanonicalModdingTargets() {
        assertEquals("1.12.2", kb.versionIndex().resolve("1.12").orElseThrow());
        assertEquals("1.20.4", kb.versionIndex().resolve("1.20.2").orElseThrow());
        assertEquals("b1.7.3", kb.versionIndex().resolve("Beta 1.7.3").orElseThrow());
    }

    // ------------------------------------------------------------ plan outcomes

    @Test
    void sameVersionNeedsNoReplacement() {
        assertInstanceOf(ReplacementPlan.None.class, ApiReplacement.plan("~26.2", "26.2", kb));
    }

    @Test
    void oneVersionBackIsASingleDocumentedStep() {
        ReplacementPlan plan = ApiReplacement.plan("~26.1", "26.2", kb);
        ReplacementPlan.Replace replace = assertInstanceOf(ReplacementPlan.Replace.class, plan);
        assertEquals("26.1", replace.fromVersion());
        assertEquals("26.2", replace.toVersion());
        assertEquals(1, replace.steps().size());
    }

    @Test
    void exclusiveUpperBoundsAreNotReadAsTargets() {
        ReplacementPlan.Replace maven = assertInstanceOf(ReplacementPlan.Replace.class,
                ApiReplacement.plan("[26.1,26.2)", "26.2", kb));
        assertEquals("26.1", maven.fromVersion());

        ReplacementPlan.Replace semver = assertInstanceOf(ReplacementPlan.Replace.class,
                ApiReplacement.plan(">=26.1 <26.2", "26.2", kb));
        assertEquals("26.1", semver.fromVersion());
    }

    @Test
    void twoVersionsBackComposeIntoAChain() {
        ReplacementPlan.Replace replace = assertInstanceOf(ReplacementPlan.Replace.class,
                ApiReplacement.plan("26.0", "26.2", kb));
        assertEquals(2, replace.steps().size());

        ChainRemapper remapper = replace.remapper();
        // 26.0 ResourceId → 26.1 Identifier → 26.2 resources/Identifier
        assertEquals("net/minecraft/resources/Identifier", remapper.map("net/minecraft/util/ResourceId"));
        assertEquals("getTickCount",
                remapper.mapMethodName("net/minecraft/server/MinecraftServer", "getTicks", "()I"));
        assertEquals("addListener",
                remapper.mapMethodName("net/neoforged/bus/api/IEventBus", "addEventListener",
                        "(Ljava/util/function/Consumer;)V"));
        // Undocumented names pass through untouched.
        assertEquals("com/example/Own", remapper.map("com/example/Own"));
    }

    @Test
    void legacyTargetIsBlockedAtItsFirstUndocumentableStep() {
        ReplacementPlan plan = ApiReplacement.plan("[1.20.1]", "26.2", kb);
        ReplacementPlan.Blocked blocked = assertInstanceOf(ReplacementPlan.Blocked.class, plan);
        assertTrue(blocked.reason().contains("breaks at 1.20.1 → 1.20.4"), blocked.reason());
    }

    @Test
    void beta173IsDocumentedAndHonestlyBlocked() {
        ReplacementPlan plan = ApiReplacement.plan("b1.7.3", "26.2", kb);
        ReplacementPlan.Blocked blocked = assertInstanceOf(ReplacementPlan.Blocked.class, plan);
        assertTrue(blocked.reason().contains("b1.7.3 → b1.8.1"), blocked.reason());
    }

    @Test
    void newerTargetThanRunningIsBlocked() {
        ReplacementPlan plan = ApiReplacement.plan("26.2", "26.1", kb);
        ReplacementPlan.Blocked blocked = assertInstanceOf(ReplacementPlan.Blocked.class, plan);
        assertTrue(blocked.reason().contains("newer"), blocked.reason());
    }

    @Test
    void unknownVersionsFallBackToNone() {
        assertInstanceOf(ReplacementPlan.None.class, ApiReplacement.plan("25.9", "26.2", kb));
        assertInstanceOf(ReplacementPlan.None.class, ApiReplacement.plan("~26.1", "unknown", kb));
        assertInstanceOf(ReplacementPlan.None.class, ApiReplacement.plan(null, "26.2", kb));
    }

    // ---------------------------------------------------------------- removals

    @Test
    void removedApisAreNamedWithTheRemovingVersionAndReplacement() {
        ReplacementPlan.Replace replace = assertInstanceOf(ReplacementPlan.Replace.class,
                ApiReplacement.plan("26.0", "26.2", kb));
        ConstantPool.ClassRefs refs = new ConstantPool.ClassRefs(
                Set.of("net/minecraft/util/LegacyRandom", "com/example/Fine"), Set.of());

        List<String> blockers = replace.removalBlockers(List.of(refs));
        assertEquals(1, blockers.size());
        assertTrue(blockers.getFirst().contains("net.minecraft.util.LegacyRandom"), blockers.getFirst());
        assertTrue(blockers.getFirst().contains("removed in 26.1"), blockers.getFirst());
        assertTrue(blockers.getFirst().contains("RandomSource"), blockers.getFirst());
    }
}
