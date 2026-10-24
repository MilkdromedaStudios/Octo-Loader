package net.minecraftforge.forgespi.language;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.objectweb.asm.Type;

/**
 * Forge's spelling of {@link net.neoforged.neoforgespi.language.ModFileScanData}:
 * what a mod's classes said about themselves, which is how a Forge mod finds
 * the classes it is meant to construct. Jade's plugins are found this way, and
 * a stand-in with no {@code memberName()} stopped it at the first one.
 */
public class ModFileScanData {
    private final Set<AnnotationData> annotations = new LinkedHashSet<>();
    private final Set<ClassData> classes = new LinkedHashSet<>();
    private final List<IModFileInfo> modFiles = new ArrayList<>();

    public Set<ClassData> getClasses() {
        return classes;
    }

    public Set<AnnotationData> getAnnotations() {
        return annotations;
    }

    public Stream<AnnotationData> getAnnotatedBy(Class<? extends Annotation> type, ElementType elementType) {
        Type annotationType = Type.getType(type);
        return annotations.stream()
                .filter(data -> data.targetType() == elementType && data.annotationType().equals(annotationType));
    }

    public void addModFileInfo(IModFileInfo info) {
        modFiles.add(info);
    }

    public List<IModFileInfo> getIModInfoData() {
        return modFiles;
    }

    public record ClassData(Type clazz, Type parent, Set<Type> interfaces) {
    }

    public record AnnotationData(Type annotationType, ElementType targetType, Type clazz, String memberName,
            Map<String, Object> annotationData) {
    }
}
