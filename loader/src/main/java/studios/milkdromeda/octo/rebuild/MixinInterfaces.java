package studios.milkdromeda.octo.rebuild;

import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.transform.ByteScan;
import studios.milkdromeda.octo.transform.MemberIndex;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Teaches a {@link MemberIndex} which interfaces mods' mixins add to the game.
 *
 * <p>A mixin that implements an interface makes its target implement it too, and
 * other mods call those methods on the game class directly — that is the whole
 * point of Fabric API's injected interfaces. None of it is in the game's class
 * files, so without this a translated mod calling one would look like it was
 * calling something the game does not have, and the call would be bypassed.
 */
public final class MixinInterfaces {
    private static final OctoLog LOG = OctoLog.of(MixinInterfaces.class);
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final ByteScan CARRIES_MIXIN = new ByteScan(List.of(MIXIN));

    private MixinInterfaces() {
    }

    public static void collect(List<LoadedMod> mods, MemberIndex index) {
        int added = 0;

        for (LoadedMod mod : mods) {
            if (mod.isBuiltin()) {
                continue;
            }

            try (ModSource source = ModSource.open(mod.effectivePath())) {
                for (String entry : source.entries()) {
                    if (!entry.endsWith(".class")) {
                        continue;
                    }

                    byte[] bytes = source.read(entry).orElse(null);

                    if (bytes == null || !CARRIES_MIXIN.matches(bytes)) {
                        continue;
                    }

                    added += register(bytes, index);
                }
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.debug("could not read {}'s mixins: {}", mod.id(), e.toString());
            }
        }

        LOG.debug("{} interface(s) are added to game classes by mods' mixins", added);
    }

    private static int register(byte[] bytes, MemberIndex index) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (node.interfaces.isEmpty()) {
            return 0;
        }

        int added = 0;

        for (List<AnnotationNode> annotations : java.util.Arrays.asList(node.invisibleAnnotations,
                node.visibleAnnotations)) {
            if (annotations == null) {
                continue;
            }

            for (AnnotationNode annotation : annotations) {
                if (!MIXIN.equals(annotation.desc) || annotation.values == null) {
                    continue;
                }

                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (!(annotation.values.get(i + 1) instanceof List<?> targets)) {
                        continue;
                    }

                    for (Object target : targets) {
                        String name = target instanceof Type type ? type.getInternalName()
                                : target instanceof String text ? text.replace('.', '/') : null;

                        if (name == null) {
                            continue;
                        }

                        for (String iface : node.interfaces) {
                            index.addInterface(name, iface);
                            added++;
                        }
                    }
                }
            }
        }

        return added;
    }
}
