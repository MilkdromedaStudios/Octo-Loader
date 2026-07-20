package studios.milkdromeda.octoloader.replace;

import studios.milkdromeda.octoloader.bytecode.ConstantPool;
import studios.milkdromeda.octoloader.knowledge.ApiStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The replacement engine's verdict for one mod's declared Minecraft target.
 */
public sealed interface ReplacementPlan {
    /** Nothing to replace: same version, undocumented version, or no usable target. */
    record None(String reason) implements ReplacementPlan {
    }

    /**
     * The chain from {@code fromVersion} to {@code toVersion} is fully
     * documented at the mechanical level; {@code steps} compose into a rewrite.
     */
    record Replace(String fromVersion, String toVersion, List<ApiStep> steps) implements ReplacementPlan {
        public ChainRemapper remapper() {
            return new ChainRemapper(steps);
        }

        /** Whether any documented rename applies to the given class file's references. */
        public boolean touches(ConstantPool.ClassRefs refs) {
            ChainRemapper remapper = remapper();
            for (String cls : refs.classes()) {
                if (!remapper.map(cls).equals(cls)) return true;
            }
            for (ConstantPool.MemberRef member : refs.members()) {
                if (remapper.rewritesMember(member.owner(), member.name(), member.desc(), member.method())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * References to APIs the documentation records as removed somewhere
         * along the chain — each entry names the API, the version that removed
         * it, and the documented replacement.
         */
        public List<String> removalBlockers(Iterable<ConstantPool.ClassRefs> allRefs) {
            List<String> blockers = new ArrayList<>();
            for (ConstantPool.ClassRefs refs : allRefs) {
                for (String cls : refs.classes()) {
                    checkClassRemoval(cls, blockers);
                }
                for (ConstantPool.MemberRef member : refs.members()) {
                    checkMemberRemoval(member, blockers);
                }
            }
            return blockers.stream().distinct().sorted().toList();
        }

        private void checkClassRemoval(String cls, List<String> blockers) {
            String current = cls;
            for (ApiStep step : steps) {
                Optional<ApiStep.RemovedApi> removed = step.removalOfClass(current);
                if (removed.isPresent()) {
                    blockers.add(describeRemoval(cls.replace('/', '.'), step, removed.get()));
                    return;
                }
                current = step.mapClass(current);
            }
        }

        private void checkMemberRemoval(ConstantPool.MemberRef member, List<String> blockers) {
            String owner = member.owner();
            String name = member.name();
            String desc = member.desc();
            for (ApiStep step : steps) {
                Optional<ApiStep.RemovedApi> removed = step.removalOfMember(owner, name);
                if (removed.isPresent()) {
                    blockers.add(describeRemoval(
                            member.owner().replace('/', '.') + "#" + member.name(), step, removed.get()));
                    return;
                }
                name = step.mapMember(owner, name, desc, member.method());
                owner = step.mapClass(owner);
            }
        }

        private static String describeRemoval(String api, ApiStep step, ApiStep.RemovedApi removed) {
            StringBuilder sb = new StringBuilder(api).append(" (removed in ").append(step.to());
            if (removed.replacement() != null) sb.append("; use ").append(removed.replacement());
            sb.append(')');
            return sb.toString();
        }

        /** Short report wording, e.g. {@code "26.0 → 26.2 (2 documented steps)"}. */
        public String description() {
            return fromVersion + " → " + toVersion
                    + " (" + steps.size() + " documented step" + (steps.size() == 1 ? "" : "s") + ")";
        }
    }

    /**
     * The target is documented but cannot be bridged: the chain hits a
     * descriptive-only step, has a documentation gap, or points forward.
     */
    record Blocked(String fromVersion, String toVersion, String reason) implements ReplacementPlan {
    }
}
