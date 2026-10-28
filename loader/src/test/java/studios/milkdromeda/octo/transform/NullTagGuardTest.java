package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A null put into a compound is left out, rather than failing when the compound is written. */
class NullTagGuardTest {
    private static final String COMPOUND = "net/minecraft/nbt/CompoundTag";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(NullTagGuardTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    @Test
    @DisplayName("put(key, null) stores nothing; put(key, tag) stores the tag")
    void nullIsLeftOut() throws Exception {
        byte[] bytes;

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(COMPOUND + ".class")) {
            bytes = in.readAllBytes();
        }

        Class<?> compound = new Defining().define(COMPOUND,
                new NullTagGuard().transform(COMPOUND, bytes, TransformContext.of(null, name -> true)));
        Object tag = compound.getConstructor().newInstance();
        Class<?> tagType = Class.forName("net.minecraft.nbt.Tag");
        var put = compound.getMethod("put", String.class, tagType);
        Object value = java.lang.reflect.Proxy.newProxyInstance(tagType.getClassLoader(), new Class<?>[] { tagType },
                (proxy, method, arguments) -> method.getName().equals("hashCode") ? 1 : null);

        put.invoke(tag, "Item", null);
        put.invoke(tag, "Kept", value);

        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) compound.getField("values").get(tag);
        assertFalse(values.containsKey("Item"), "a null would fail the whole compound when it is written");
        assertTrue(values.containsKey("Kept"));
        assertEquals(1, values.size());
    }
}
