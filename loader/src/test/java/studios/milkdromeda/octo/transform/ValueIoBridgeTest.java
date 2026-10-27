package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.octotest.Machine3;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A block entity from 1.21.1, two classes deep, saved and loaded by a game
 * that only calls the {@code ValueOutput} and {@code ValueInput} methods.
 */
class ValueIoBridgeTest {
    private static final String MACHINE = "mod/Machine";
    private static final String FAST = "mod/FastMachine";
    private static final String GAME = "net/minecraft/octotest/Machine3";
    private static final String TAG = "net/minecraft/nbt/CompoundTag";
    private static final String OLD = "(L" + TAG + ";Lnet/minecraft/core/HolderLookup$Provider;)V";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(ValueIoBridgeTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static byte[] resource(String internalName) {
        try (InputStream in = ValueIoBridgeTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static MethodVisitor begin(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PROTECTED, name, descriptor, null, null);
        method.visitCode();
        return method;
    }

    private static void end(MethodVisitor method) {
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void constructor(ClassWriter writer, String superName) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        end(method);
    }

    private static void putInt(MethodVisitor method, String key, int value) {
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitLdcInsn(key);
        method.visitIntInsn(Opcodes.BIPUSH, value);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TAG, "putInt", "(Ljava/lang/String;I)V", false);
    }

    /** Saves {@code speed}, and loads it, counting the loads. */
    private static byte[] machine() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MACHINE, null, GAME, null);
        writer.visitField(Opcodes.ACC_PUBLIC, "speed", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "loads", "I", null, null).visitEnd();
        constructor(writer, GAME);

        MethodVisitor save = begin(writer, "saveAdditional", OLD);
        putInt(save, "speed", 16);
        end(save);

        MethodVisitor load = begin(writer, "loadAdditional", OLD);
        load.visitVarInsn(Opcodes.ALOAD, 0);
        load.visitVarInsn(Opcodes.ALOAD, 1);
        load.visitLdcInsn("speed");
        load.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TAG, "getInt", "(Ljava/lang/String;)I", false);
        load.visitFieldInsn(Opcodes.PUTFIELD, MACHINE, "speed", "I");
        load.visitVarInsn(Opcodes.ALOAD, 0);
        load.visitInsn(Opcodes.DUP);
        load.visitFieldInsn(Opcodes.GETFIELD, MACHINE, "loads", "I");
        load.visitInsn(Opcodes.ICONST_1);
        load.visitInsn(Opcodes.IADD);
        load.visitFieldInsn(Opcodes.PUTFIELD, MACHINE, "loads", "I");
        end(load);

        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Saves what its superclass does, then {@code boost}. */
    private static byte[] fastMachine() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, FAST, null, MACHINE, null);
        constructor(writer, MACHINE);

        MethodVisitor save = begin(writer, "saveAdditional", OLD);
        save.visitVarInsn(Opcodes.ALOAD, 0);
        save.visitVarInsn(Opcodes.ALOAD, 1);
        save.visitVarInsn(Opcodes.ALOAD, 2);
        save.visitMethodInsn(Opcodes.INVOKESPECIAL, MACHINE, "saveAdditional", OLD, false);
        putInt(save, "boost", 2);
        end(save);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Class<?>[] bridged() {
        Map<String, byte[]> mod = new HashMap<>();
        mod.put(MACHINE, machine());
        mod.put(FAST, fastMachine());
        ValueIoBridge bridge = new ValueIoBridge(new MemberIndex(name -> mod.containsKey(name) ? mod.get(name)
                : resource(name)));
        TransformContext context = TransformContext.of(null, name -> true);
        Defining loader = new Defining();
        return new Class<?>[] { loader.define(MACHINE, bridge.transform(MACHINE, mod.get(MACHINE), context)),
                loader.define(FAST, bridge.transform(FAST, mod.get(FAST), context)) };
    }

    private static void call(Object target, String name, Class<?> parameter, Object argument) throws Exception {
        Method method = Machine3.class.getDeclaredMethod(name, parameter);
        method.setAccessible(true);
        method.invoke(target, argument);
    }

    @Test
    @DisplayName("the game's save reaches the old method's whole chain, once, after the game's own part")
    void saveReachesTheOldChain() throws Exception {
        Class<?>[] classes = bridged();
        Object fast = classes[1].getConstructor().newInstance();
        TagValueOutput output = new TagValueOutput();

        call(fast, "saveAdditional", ValueOutput.class, output);

        assertEquals(Map.of("base", 1, "speed", 16, "boost", 2), output.buildResult().values);
        classes[0].getDeclaredMethod("saveAdditional", ValueOutput.class);
        assertThrows(NoSuchMethodException.class,
                () -> classes[1].getDeclaredMethod("saveAdditional", ValueOutput.class),
                "only the first of the mod's classes is bridged, or the chain would run twice");
    }

    @Test
    @DisplayName("the game's load hands the old method the tag being read")
    void loadReadsTheTag() throws Exception {
        Object fast = bridged()[1].getConstructor().newInstance();
        CompoundTag tag = new CompoundTag();
        tag.putInt("speed", 7);

        call(fast, "loadAdditional", ValueInput.class, new TagValueInput(tag));

        assertEquals(7, fast.getClass().getField("speed").getInt(fast));
        assertEquals(1, fast.getClass().getField("loads").getInt(fast), "loaded once");
        assertEquals(1, ((Machine3) fast).baseLoads, "the game's own load ran too");
    }
}
