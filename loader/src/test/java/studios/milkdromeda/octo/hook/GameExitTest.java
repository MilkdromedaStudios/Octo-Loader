package studios.milkdromeda.octo.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import studios.milkdromeda.octo.transform.GameExitTransformer;
import studios.milkdromeda.octo.transform.TransformContext;

/**
 * The game leaving, and Octo getting a word in first.
 *
 * <p>What is being proved here is that the rewritten call runs before the JVM
 * goes anywhere — which is the whole point of it. A window put up after the exit
 * has already been taken cannot be kept on screen on Windows, however long the
 * process is held open behind it, and that is what a player saw: a report window
 * for about a second, then a launcher saying "Exit code: -1".
 */
class GameExitTest {
    /** A class named like the game's, so the transformer treats it as the game's. */
    private static final String GAME_CLASS = "net/minecraft/client/main/Main";

    private final AtomicInteger exited = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger halted = new AtomicInteger(Integer.MIN_VALUE);

    private static final class Defining extends ClassLoader {
        Defining() {
            super(GameExitTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    @BeforeEach
    void watchTheExitsInsteadOfTakingThem() {
        GameExit.takeOverForTest(exited::set, halted::set);
    }

    @AfterEach
    void handTheExitsBack() {
        GameExit.releaseForTest();
    }

    @Test
    @DisplayName("the game's System.exit arrives at Octo, and with the status it asked for")
    void systemExitIsRerouted() throws Exception {
        run("quit");

        assertEquals(-1, exited.get(), "the crash exit should have gone through the loader");
        assertEquals(Integer.MIN_VALUE, halted.get());
    }

    @Test
    @DisplayName("Runtime.exit and Runtime.halt go the same way, receiver and all")
    void theRuntimeSpellingsAreReroutedToo() throws Exception {
        // The receiver sits under the status on the stack; getting that wrong is
        // a class that passes ASM and then fails the verifier at load.
        run("quitByRuntime");
        assertEquals(3, exited.get());

        run("kill");
        assertEquals(9, halted.get());
    }

    @Test
    @DisplayName("a mod's own exit is left alone: a mod that quits means it")
    void onlyTheGameIsRewritten() {
        byte[] original = gameThatExits("com/example/quitmod/Quitter");

        assertSame(original, transform("com/example/quitmod/Quitter", original),
                "only the game's exits are Octo's to intercept");
    }

    @Test
    @DisplayName("a game class that never exits is handed back untouched")
    void nothingToRewrite() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/Quiet", null, "java/lang/Object", null);

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "hello", "()V",
                null, null);
        method.visitCode();
        // Mentions java/lang/System without exiting, which is the case the cheap
        // prefilter says yes to and the rewrite has to say no to.
        method.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();

        byte[] original = writer.toByteArray();

        assertSame(original, transform("net/minecraft/Quiet", original));
    }

    private void run(String method) throws Exception {
        Class<?> game = new Defining().define(GAME_CLASS, transform(GAME_CLASS, gameThatExits(GAME_CLASS)));
        Method entry = game.getDeclaredMethod(method);
        entry.setAccessible(true);
        entry.invoke(null);
    }

    private byte[] transform(String internalName, byte[] bytes) {
        return new GameExitTransformer().transform(internalName, bytes, TransformContext.of(null, name -> true));
    }

    /** The three ways Minecraft's own code ends the process. */
    private byte[] gameThatExits(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor quit = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "quit", "()V", null, null);
        quit.visitCode();
        quit.visitInsn(Opcodes.ICONST_M1);
        quit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "exit", "(I)V", false);
        quit.visitInsn(Opcodes.RETURN);
        quit.visitMaxs(0, 0);
        quit.visitEnd();

        MethodVisitor byRuntime = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "quitByRuntime",
                "()V", null, null);
        byRuntime.visitCode();
        byRuntime.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime",
                "()Ljava/lang/Runtime;", false);
        byRuntime.visitInsn(Opcodes.ICONST_3);
        byRuntime.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "exit", "(I)V", false);
        byRuntime.visitInsn(Opcodes.RETURN);
        byRuntime.visitMaxs(0, 0);
        byRuntime.visitEnd();

        MethodVisitor kill = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "kill", "()V", null, null);
        kill.visitCode();
        kill.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime",
                "()Ljava/lang/Runtime;", false);
        kill.visitIntInsn(Opcodes.BIPUSH, 9);
        kill.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "halt", "(I)V", false);
        kill.visitInsn(Opcodes.RETURN);
        kill.visitMaxs(0, 0);
        kill.visitEnd();

        writer.visitEnd();

        return writer.toByteArray();
    }
}
