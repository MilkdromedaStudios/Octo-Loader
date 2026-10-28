package net.neoforged.fml.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * FML's reflection helpers: find a member by name and make it accessible.
 *
 * <p>Names are taken as they are; the game has been unobfuscated since 26.x,
 * and a translated mod's names were brought up to date when it was rebuilt.
 * Moonlight finds every one of NeoForge's config internals through these,
 * and a null for each of them failed it and every mod built on it.
 */
public class ObfuscationReflectionHelper {
    @SuppressWarnings("unchecked")
    public static <T, E> T getPrivateValue(Class<? super E> classToAccess, E instance, String fieldName) {
        try {
            return (T) findField(classToAccess, fieldName).get(instance);
        } catch (UnableToFindFieldException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new UnableToAccessFieldException(e);
        }
    }

    public static <T, E> void setPrivateValue(Class<? super T> classToAccess, T instance, E value, String fieldName) {
        try {
            Field field = findField(classToAccess, fieldName);

            // A null where the game now keeps a name made from the entry's
            // own id: Registrate clears a copied block's loot table, which
            // was a key until 26.x and null meant "the block's own"; it is a
            // DependantName now, whose default already is the block's own,
            // and a null there failed every block Create builds.
            if (value == null && field.getType().getName().equals("net.minecraft.resources.DependantName")) {
                return;
            }

            field.set(instance, value);
        } catch (UnableToFindFieldException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new UnableToAccessFieldException(e);
        }
    }

    public static Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new UnableToFindMethodException(e);
        }
    }

    public static <T> Constructor<T> findConstructor(Class<T> clazz, Class<?>... parameterTypes) {
        try {
            Constructor<T> constructor = clazz.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new UnknownConstructorException(clazz.getName() + " has no such constructor", e);
        }
    }

    public static <T> Field findField(Class<? super T> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new UnableToFindFieldException(e);
        }
    }

    public static class UnableToAccessFieldException extends RuntimeException {
        private UnableToAccessFieldException(Exception e) {
            super(e);
        }
    }

    public static class UnableToFindFieldException extends RuntimeException {
        private UnableToFindFieldException(Exception e) {
            super(e);
        }
    }

    public static class UnableToFindMethodException extends RuntimeException {
        public UnableToFindMethodException(Throwable failed) {
            super(failed);
        }
    }

    public static class UnknownConstructorException extends RuntimeException {
        public UnknownConstructorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
