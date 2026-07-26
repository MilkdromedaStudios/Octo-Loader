package studios.milkdromeda.octo.compat.fabric;

import java.util.Collection;
import java.util.List;

import net.fabricmc.loader.api.MappingResolver;

import studios.milkdromeda.octo.compat.mapping.MappingRegistry;

/**
 * Fabric's name-mapping service, answered from Octo's mapping tables.
 *
 * <p>Mods use this to translate an intermediary name into whatever the runtime
 * actually uses. Octo already has to hold those tables to translate old mods,
 * so the same data serves both.
 */
public final class OctoMappingResolver implements MappingResolver {
    @Override
    public Collection<String> getNamespaces() {
        return List.of("official", "intermediary", "named", "searge", "mojang");
    }

    @Override
    public String getCurrentRuntimeNamespace() {
        return MappingRegistry.get().runtimeNamespace();
    }

    @Override
    public String mapClassName(String namespace, String className) {
        return MappingRegistry.get().mapClassName(namespace, className);
    }

    @Override
    public String unmapClassName(String targetNamespace, String className) {
        return MappingRegistry.get().unmapClassName(targetNamespace, className);
    }

    @Override
    public String mapFieldName(String namespace, String owner, String name, String descriptor) {
        return MappingRegistry.get().mapFieldName(namespace, owner, name, descriptor);
    }

    @Override
    public String mapMethodName(String namespace, String owner, String name, String descriptor) {
        return MappingRegistry.get().mapMethodName(namespace, owner, name, descriptor);
    }
}
