package studios.milkdromeda.octo.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.neoforged.fml.loading.modscan.ModAnnotation;

import studios.milkdromeda.octo.mod.BytecodeIndex.ScannedAnnotation;
import studios.milkdromeda.octo.testkit.SourceCompiler;

/**
 * The index a Forge or NeoForge mod reads to find its own plugin classes.
 *
 * <p>None of this is Octo's own shape: the names and the value types are what
 * FML puts in this table, because they are handed to mods unchanged and the mods
 * were compiled against FML. Getting the member naming wrong means a mod finds
 * nothing and says so with an empty screen rather than an error.
 */
class BytecodeIndexTest {
    @Test
    @DisplayName("an annotation on a class is filed under the class's own name, which is what mods look up")
    void classAnnotationsCarryTheClassName() {
        List<ScannedAnnotation> found = scan();
        ScannedAnnotation plugin = one(found, "Lcom/example/Marker;", ScannedAnnotation.Target.TYPE);

        assertEquals("com.example.Plugin", plugin.member(),
                "a mod reads memberName() to know what to instantiate, so it must be the class");
        assertEquals("com/example/Plugin", plugin.className());
    }

    @Test
    @DisplayName("an annotation on a method carries its descriptor, so overloads stay apart")
    void methodAnnotationsCarryTheirDescriptor() {
        ScannedAnnotation method = one(scan(), "Lcom/example/Marker;", ScannedAnnotation.Target.METHOD);

        assertEquals("handle(Ljava/lang/String;)V", method.member());
    }

    @Test
    @DisplayName("an annotation on a field carries the field's name")
    void fieldAnnotationsCarryTheirName() {
        ScannedAnnotation field = one(scan(), "Lcom/example/Marker;", ScannedAnnotation.Target.FIELD);

        assertEquals("counter", field.member());
    }

    @Test
    @DisplayName("members read back as mods expect: plain values, arrays as lists, enums as the holder")
    void valuesKeepTheShapeModsCastTo() {
        ScannedAnnotation annotated = one(scan(), "Lcom/example/Detailed;", ScannedAnnotation.Target.TYPE);
        Map<String, Object> values = annotated.values();

        assertEquals("a plugin", values.get("name"));
        assertEquals(3, values.get("weight"));
        assertEquals(List.of("one", "two"), values.get("tags"));

        // An enum constant cannot be the enum itself: scanning reads bytecode and
        // never loads a class. NeoForge puts this pair in its place, so a mod
        // reading such a member casts to it.
        assertEquals(new ModAnnotation.EnumHolder("Lcom/example/Phase;", "LATE"), values.get("phase"));
    }

    @Test
    @DisplayName("an annotation the compiler did not retain is still indexed, because scanning reads bytecode")
    void keepsAnnotationsThatDoNotSurviveToRuntime() {
        ScannedAnnotation invisible = one(scan(), "Lcom/example/Invisible;", ScannedAnnotation.Target.TYPE);

        assertNotNull(invisible, "a class-retained annotation is readable in the jar, so mods can and do use it");
        assertEquals("com.example.Plugin", invisible.member());
    }

    @Test
    @DisplayName("every class is indexed with its parent and interfaces")
    void classesCarryTheirHierarchy() {
        ScanResult result = scanned();

        assertTrue(result.classes().stream().anyMatch(scanned -> scanned.internalName().equals("com/example/Plugin")
                        && "java/lang/Object".equals(scanned.superName())
                        && scanned.interfaces().contains("java/lang/Runnable")),
                "the scanned classes should describe the hierarchy: " + result.classes());
    }

    // ------------------------------------------------------------- fixtures

    private static List<ScannedAnnotation> scan() {
        return scanned().annotations();
    }

    private static ScanResult scanned() {
        Map<String, byte[]> compiled = SourceCompiler.compile(Map.of(
                "com.example.Marker", """
                        package com.example;

                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;
                        import java.lang.annotation.Target;

                        @Retention(RetentionPolicy.RUNTIME)
                        @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
                        public @interface Marker {
                        }
                        """,
                "com.example.Invisible", """
                        package com.example;

                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;
                        import java.lang.annotation.Target;

                        @Retention(RetentionPolicy.CLASS)
                        @Target(ElementType.TYPE)
                        public @interface Invisible {
                        }
                        """,
                "com.example.Phase", """
                        package com.example;

                        public enum Phase {
                            EARLY, LATE
                        }
                        """,
                "com.example.Detailed", """
                        package com.example;

                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;
                        import java.lang.annotation.Target;

                        @Retention(RetentionPolicy.RUNTIME)
                        @Target(ElementType.TYPE)
                        public @interface Detailed {
                            String name();

                            int weight();

                            String[] tags();

                            Phase phase();
                        }
                        """,
                "com.example.Plugin", """
                        package com.example;

                        @Marker
                        @Invisible
                        @Detailed(name = "a plugin", weight = 3, tags = {"one", "two"}, phase = Phase.LATE)
                        public class Plugin implements Runnable {
                            @Marker
                            public int counter;

                            @Marker
                            public void handle(String argument) {
                            }

                            @Override
                            public void run() {
                            }
                        }
                        """));

        return new ModClassScanner().scanClasses(new ArrayList<>(compiled.values()));
    }

    private static ScannedAnnotation one(List<ScannedAnnotation> found, String descriptor,
            ScannedAnnotation.Target target) {
        List<ScannedAnnotation> matches = found.stream()
                .filter(annotation -> annotation.descriptor().equals(descriptor) && annotation.target() == target)
                .toList();

        assertEquals(1, matches.size(), "expected exactly one " + descriptor + " on a " + target + ": " + found);
        return matches.get(0);
    }
}
