package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.component.DataComponentMap;

/**
 * A stack made before 26.x has bound its items' components: the holder hands
 * out a view that is empty, and becomes the components once they are bound.
 */
class UnboundComponentsTest {
    private static final String REFERENCE = "net/minecraft/core/Holder$Reference";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(UnboundComponentsTest.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    private static byte[] classBytes(String internalName) throws IOException {
        try (InputStream in = UnboundComponentsTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            return in.readAllBytes();
        }
    }

    private static Class<?> reference(boolean patched) throws IOException {
        byte[] bytes = classBytes(REFERENCE);

        if (patched) {
            bytes = new UnboundComponents().transform(REFERENCE, bytes, TransformContext.of(null, name -> true));
        }

        return new Defining().define(REFERENCE.replace('/', '.'), bytes);
    }

    private static DataComponentMap components(Object holder) throws Exception {
        try {
            return (DataComponentMap) holder.getClass().getMethod("components").invoke(holder);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static DataComponentMap of(Map<Object, Object> values) {
        return new DataComponentMap() {
            @Override
            public Set<Object> keySet() {
                return values.keySet();
            }

            @Override
            public Object get(Object type) {
                return values.get(type);
            }
        };
    }

    @Test
    @DisplayName("unpatched, an unbound holder's components fail, as 26.x has them")
    void unpatchedFails() throws Exception {
        Object holder = reference(false).getConstructor().newInstance();

        assertThrows(NullPointerException.class, () -> components(holder));
    }

    @Test
    @DisplayName("an unbound holder's components are an empty view that becomes them once bound")
    void viewReadsThroughOnceBound() throws Exception {
        Class<?> type = reference(true);
        Object holder = type.getConstructor().newInstance();
        DataComponentMap early = components(holder);

        assertEquals(0, early.size(), "nothing is bound yet");
        assertFalse(early.has("max_stack_size"));
        assertSame(early, components(holder), "one view per holder");

        DataComponentMap bound = of(Map.of("max_stack_size", 64));
        type.getMethod("bindComponents", DataComponentMap.class).invoke(holder, bound);

        assertTrue(early.has("max_stack_size"), "the view made early reads the components bound later");
        assertEquals(64, early.get("max_stack_size"));
        assertEquals(1, early.size());
        assertSame(bound, components(holder), "a bound holder hands out its own components");
    }
}
