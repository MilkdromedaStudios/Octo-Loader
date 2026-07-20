package studios.milkdromeda.octoloader.replace;

import org.objectweb.asm.commons.Remapper;
import studios.milkdromeda.octoloader.knowledge.ApiStep;

import java.util.List;

/**
 * Composes the documented renames of a sequence of version steps into one ASM
 * {@link Remapper}: a reference is carried forward step by step, each lookup
 * happening in that step's "from" namespace (owners and descriptors are
 * remapped alongside so later steps see the names of their own era).
 */
public final class ChainRemapper extends Remapper {
    private final List<ApiStep> steps;
    private final Remapper[] perStep;

    public ChainRemapper(List<ApiStep> steps) {
        this.steps = List.copyOf(steps);
        this.perStep = new Remapper[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            ApiStep step = steps.get(i);
            perStep[i] = new Remapper() {
                @Override
                public String map(String internalName) {
                    return step.mapClass(internalName);
                }
            };
        }
    }

    @Override
    public String map(String internalName) {
        String name = internalName;
        for (ApiStep step : steps) name = step.mapClass(name);
        return name;
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        return mapMemberName(owner, name, descriptor, true);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return mapMemberName(owner, name, descriptor, false);
    }

    private String mapMemberName(String owner, String name, String desc, boolean method) {
        String o = owner, n = name, d = desc;
        for (int i = 0; i < steps.size(); i++) {
            ApiStep step = steps.get(i);
            n = step.mapMember(o, n, d, method);
            o = step.mapClass(o);
            d = method ? perStep[i].mapMethodDesc(d) : perStep[i].mapDesc(d);
        }
        return n;
    }

    /** Whether any documented rename in the chain applies to the given member. */
    public boolean rewritesMember(String owner, String name, String desc, boolean method) {
        return !mapMemberName(owner, name, desc, method).equals(name);
    }
}
