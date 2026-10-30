package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The calls NeoForge's patched game makes, put back into an unpatched one:
 * its game events and its mods' packets. The game classes are assembled in
 * the shape the hooks look for, and the JVM's verifier judges the result.
 */
class GameHooksTransformTest {
    private static final class Defining extends ClassLoader {
        Defining() {
            super(GameHooksTransformTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static final TransformContext GAME = TransformContext.of(null, name -> true);

    private static List<String> calls(byte[] bytes, String method, String helper) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> out = new ArrayList<>();

        for (MethodNode candidate : node.methods) {
            if (candidate.name.equals(method)) {
                for (AbstractInsnNode insn : candidate.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals(helper)) {
                        out.add(call.name);
                    }
                }
            }
        }

        return out;
    }

    /** {@code Minecraft.tick()} with two ways out, so each return gets the closing call. */
    private static byte[] minecraft() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/Minecraft", null, "java/lang/Object",
                null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor tick = writer.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        tick.visitCode();
        Label late = new Label();
        tick.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
        tick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
        tick.visitJumpInsn(Opcodes.IFEQ, late);
        tick.visitInsn(Opcodes.RETURN);
        tick.visitLabel(late);
        tick.visitInsn(Opcodes.RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("the client tick fires its pre event at the start and its post event at every return")
    void clientTickIsHooked() throws Exception {
        byte[] hooked = new GameEventHooks().transform("net/minecraft/client/Minecraft", minecraft(), GAME);

        assertEquals(List.of("clientTick", "clientTick", "clientTick"),
                calls(hooked, "tick", "studios/milkdromeda/octo/bridge/forge/NeoGameEvents"));

        // With no NeoForge on the path the calls do nothing, and the class runs.
        Class<?> type = new Defining().define("net/minecraft/client/Minecraft", hooked);
        type.getMethod("tick").invoke(type.getConstructor().newInstance());
    }

    /** {@code CustomPacketPayload}'s codec, cut down to its lookup. */
    private static byte[] payloadCodec() {
        String name = "net/minecraft/network/protocol/common/custom/CustomPacketPayload$1";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_FINAL, "val$idToType", "Ljava/util/Map;", null, null).visitEnd();

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/Map;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, name, "val$idToType", "Ljava/util/Map;");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // findCodec(id): the game's own map, and nothing when it misses.
        MethodVisitor find = writer.visitMethod(Opcodes.ACC_PUBLIC, "findCodec",
                "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/network/codec/StreamCodec;", null, null);
        find.visitCode();
        find.visitVarInsn(Opcodes.ALOAD, 0);
        find.visitFieldInsn(Opcodes.GETFIELD, name, "val$idToType", "Ljava/util/Map;");
        find.visitVarInsn(Opcodes.ALOAD, 1);
        find.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;",
                true);
        find.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/network/codec/StreamCodec");
        find.visitInsn(Opcodes.ARETURN);
        find.visitMaxs(0, 0);
        find.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("the payload codec asks for a mod's codec first, with frames the verifier accepts")
    void payloadCodecIsHooked() throws Exception {
        String name = "net/minecraft/network/protocol/common/custom/CustomPacketPayload$1";
        byte[] original = payloadCodec();
        byte[] hooked = new PayloadHooks().transform(name, original, GAME);

        assertNotSame(original, hooked);
        assertEquals(List.of("codec"), calls(hooked, "findCodec", "studios/milkdromeda/octo/bridge/forge/ModPayloads"));

        // No mod registered anything here: the game's lookup answers, as before.
        Class<?> type = new Defining().define(name, hooked);
        Object codec = type.getConstructor(java.util.Map.class).newInstance(new HashMap<>());
        Method find = type.getDeclaredMethod("findCodec", net.minecraft.resources.Identifier.class);
        assertEquals(null, find.invoke(codec, new net.minecraft.resources.Identifier()));
    }
}
