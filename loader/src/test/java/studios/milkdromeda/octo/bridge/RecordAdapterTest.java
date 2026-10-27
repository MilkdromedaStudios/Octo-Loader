package studios.milkdromeda.octo.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An object written against an interface that is a record now, handed where the record goes. */
class RecordAdapterTest {
    /** As 26.x's RecipeSerializer: a record of what the interface's methods answered. */
    public record Serializer(String codec, String streamCodec) {
    }

    /** As a mod's serializer, which implemented the interface. */
    public static final class OldSerializer {
        public String codec() {
            return "map codec";
        }

        public String streamCodec() {
            return "stream codec";
        }
    }

    @Test
    @DisplayName("the record is built from what the object answers, and is the same record every time")
    void builtFromAccessors() {
        OldSerializer old = new OldSerializer();
        Object made = RecordAdapter.adapt(old, Serializer.class);

        assertEquals(new Serializer("map codec", "stream codec"), made);
        assertSame(made, RecordAdapter.adapt(old, Serializer.class), "the registry sees one object per serializer");
    }

    @Test
    @DisplayName("a record already, or null, is handed back as it is")
    void alreadyARecord() {
        Serializer record = new Serializer("a", "b");
        assertSame(record, RecordAdapter.adapt(record, Serializer.class));
        assertSame(null, RecordAdapter.adapt(null, Serializer.class));
    }

    @Test
    @DisplayName("an object that cannot answer the record fails as the cast would")
    void unanswerableFailsAsACast() {
        assertThrows(ClassCastException.class, () -> RecordAdapter.adapt("not a serializer", Serializer.class));
    }
}
