package studios.milkdromeda.octoloader.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The documented API changes of one version step ({@code from} → {@code to}).
 *
 * <p>Coverage comes in two honesty levels:
 * <ul>
 *   <li>{@code "full"} — the step's changes are mechanical (class/member
 *       renames, removals) and Octo can rewrite a mod's compiled references
 *       across it;</li>
 *   <li>{@code "descriptive"} — the documentation explains what changed, but
 *       the change is not mechanically bridgeable (obfuscation reshuffles,
 *       wholesale rewrites); mods can only be reported, not replaced.</li>
 * </ul>
 *
 * @param from            version this step starts at
 * @param to              version this step ends at
 * @param coverage        {@code "full"} or {@code "descriptive"}
 * @param runtimeMappings mapping namespace mods of the {@code from} era ran on
 * @param summary         one-line explanation used in reports
 * @param classRenames    internal-name renames, keys in {@code from}-era names
 * @param memberRenames   method/field renames, owners and descs in {@code from}-era names
 * @param removedApis     APIs that ceased to exist in {@code to}
 * @param notableChanges  extra report-only notes for descriptive steps
 */
public record ApiStep(
        String from,
        String to,
        String coverage,
        String runtimeMappings,
        String summary,
        Map<String, String> classRenames,
        List<MemberRename> memberRenames,
        List<RemovedApi> removedApis,
        List<String> notableChanges
) {
    /**
     * @param kind    {@code "method"} or {@code "field"}
     * @param owner   internal name of the declaring class, in {@code from}-era names
     * @param name    member name before the step
     * @param desc    descriptor before the step, or {@code null} to match any
     * @param newName member name after the step
     */
    public record MemberRename(String kind, String owner, String name, String desc, String newName) {
        public boolean isMethod() {
            return "method".equals(kind);
        }
    }

    /**
     * @param api         internal class name, or {@code owner#member} for a single member
     * @param replacement what to use instead, or {@code null} if nothing
     * @param note        report wording
     */
    public record RemovedApi(String api, String replacement, String note) {
    }

    public boolean full() {
        return "full".equals(coverage);
    }

    /** Maps an internal class name across this step (identity when undocumented). */
    public String mapClass(String internalName) {
        return classRenames.getOrDefault(internalName, internalName);
    }

    /** Maps a member name across this step (identity when undocumented). */
    public String mapMember(String owner, String name, String desc, boolean method) {
        for (MemberRename rename : memberRenames) {
            if (rename.isMethod() == method && rename.owner().equals(owner) && rename.name().equals(name)
                    && (rename.desc() == null || rename.desc().equals(desc))) {
                return rename.newName();
            }
        }
        return name;
    }

    public Optional<RemovedApi> removalOfClass(String internalName) {
        for (RemovedApi removed : removedApis) {
            if (removed.api().equals(internalName)) return Optional.of(removed);
        }
        return Optional.empty();
    }

    public Optional<RemovedApi> removalOfMember(String owner, String name) {
        String key = owner + "#" + name;
        for (RemovedApi removed : removedApis) {
            if (removed.api().equals(key)) return Optional.of(removed);
        }
        return Optional.empty();
    }
}
