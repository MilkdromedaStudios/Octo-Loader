package studios.milkdromeda.octo.bridge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelAtlasesTest {
    @AfterEach
    void reset() {
        ModelAtlases.legacyNamespaces(List.of());
    }

    @Test
    void theGameItselfIsNeverALegacyNamespace() {
        ModelAtlases.legacyNamespaces(List.of("create", "minecraft", "", "ponder"));

        assertEquals(Set.of("create", "ponder"), ModelAtlases.legacyNamespaces());
    }

    @Test
    void theNamespacePatternMatchesExactlyTheLegacyNamespaces() {
        Pattern legacy = Pattern.compile(ModelAtlases.namespacePattern(Set.of("create", "ponder")));

        assertTrue(legacy.matcher("create").matches());
        assertTrue(legacy.matcher("ponder").matches());
        assertFalse(legacy.matcher("createaddition").matches());
        assertFalse(legacy.matcher("minecraft").matches());
    }

    @Test
    void theOtherPatternMatchesEverythingElse() {
        Pattern other = Pattern.compile(ModelAtlases.otherNamespacePattern(Set.of("create", "my.mod")));

        assertFalse(other.matcher("create").matches());
        assertFalse(other.matcher("my.mod").matches());
        assertTrue(other.matcher("minecraft").matches());
        assertTrue(other.matcher("createaddition").matches());
        assertTrue(other.matcher("myxmod").matches());
    }

    @Test
    void withNoLegacyModsTheAtlasIsLeftAlone() {
        Object list = new Object();

        assertSame(list, ModelAtlases.withLegacyItems(list, "minecraft:blocks"));
    }
}
