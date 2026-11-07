package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** A registration class whose middle entry this version cannot build, as Create's data components have. */
class StaticInitGuardTest {
    private static byte[] registrations() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/AllComponents", null, "java/lang/Object", null);

        for (String field : new String[] { "FIRST", "BROKEN", "LAST" }) {
            FieldVisitor visitor = writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, field,
                    "Ljava/lang/String;", null, null);
            visitor.visitEnd();
        }

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn("first");
        init.visitFieldInsn(Opcodes.PUTSTATIC, "mod/AllComponents", "FIRST", "Ljava/lang/String;");
        // A codec that came out null, used one call later.
        init.visitInsn(Opcodes.ACONST_NULL);
        init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, "mod/AllComponents", "BROKEN", "Ljava/lang/String;");
        init.visitLdcInsn("last");
        init.visitFieldInsn(Opcodes.PUTSTATIC, "mod/AllComponents", "LAST", "Ljava/lang/String;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("one failing static field leaves that field unset and the rest of the class initialised")
    void failingFieldsAreSkipped() throws Exception {
        byte[] guarded = new StaticInitGuard().transform("mod/AllComponents", registrations(),
                TransformContext.of(null, name -> true));
        Class<?> type = new ClassLoader(StaticInitGuardTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass("mod.AllComponents", guarded, 0, guarded.length);
            }
        }.define();

        assertEquals("first", type.getField("FIRST").get(null));
        assertNull(type.getField("BROKEN").get(null));
        assertEquals("last", type.getField("LAST").get(null));
    }

    /** Stands for the game: there only once it has started. */
    public static final class Game {
        public static volatile boolean started;

        public static String handler() {
            if (!started) {
                throw new NullPointerException("Minecraft.getInstance() is null");
            }

            return "built";
        }
    }

    /** A client class whose handler needs the game, as Create's CreateClient builds its schematic handler. */
    private static byte[] clientClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/ModClient", null, "java/lang/Object", null);

        for (String field : new String[] { "SCREEN", "HANDLER" }) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, field,
                    "Ljava/lang/String;", null, null).visitEnd();
        }

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn("net/minecraft/client/gui/screens/Screen");
        init.visitFieldInsn(Opcodes.PUTSTATIC, "mod/ModClient", "SCREEN", "Ljava/lang/String;");
        init.visitMethodInsn(Opcodes.INVOKESTATIC, Game.class.getName().replace('.', '/'), "handler",
                "()Ljava/lang/String;", false);
        init.visitFieldInsn(Opcodes.PUTSTATIC, "mod/ModClient", "HANDLER", "Ljava/lang/String;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("a client class's field that failed before the game started is built once it has")
    void clientFieldsAreBuiltOnceTheGameStarts() throws Exception {
        Game.started = false;
        byte[] guarded = new StaticInitGuard().transform("mod/ModClient", clientClass(),
                TransformContext.of(null, name -> true));
        Class<?> type = new ClassLoader(StaticInitGuardTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass("mod.ModClient", guarded, 0, guarded.length);
            }
        }.define();

        assertNull(type.getField("HANDLER").get(null), "the game is not there yet");

        Game.started = true;
        GapBridge.retryDeferred();
        assertEquals("built", type.getField("HANDLER").get(null));
        assertEquals("net/minecraft/client/gui/screens/Screen", type.getField("SCREEN").get(null));
    }
}
