package net.fabricmc.loader.impl.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/**
 * Fabric's default entrypoint adapter, implemented on Octo's class loading.
 *
 * <p>Upstream's {@code LanguageAdapter.getDefault()} returns
 * {@code DefaultLanguageAdapter.INSTANCE} by name, so this class has to exist
 * here for the imported API to link.
 */
public final class DefaultLanguageAdapter implements LanguageAdapter {
    public static final DefaultLanguageAdapter INSTANCE = new DefaultLanguageAdapter();

    private DefaultLanguageAdapter() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        String[] parts = value.split("::", 2);
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Class<?> owner;

        try {
            owner = Class.forName(parts[0], true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new LanguageAdapterException("could not load " + parts[0], e);
        }

        try {
            if (parts.length == 1) {
                return (T) owner.getDeclaredConstructor().newInstance();
            }

            String member = parts[1];

            for (Field field : owner.getDeclaredFields()) {
                if (field.getName().equals(member) && Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return (T) field.get(null);
                }
            }

            for (Method method : owner.getDeclaredMethods()) {
                if (method.getName().equals(member) && method.getParameterCount() == 0
                        && Modifier.isStatic(method.getModifiers())) {
                    method.setAccessible(true);
                    return (T) method.invoke(null);
                }
            }

            throw new LanguageAdapterException(owner.getName() + " has no static member " + member);
        } catch (ReflectiveOperationException e) {
            throw new LanguageAdapterException("could not create " + value, e);
        }
    }
}
