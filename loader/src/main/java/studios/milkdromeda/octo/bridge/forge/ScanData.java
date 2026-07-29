package studios.milkdromeda.octo.bridge.forge;

import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.Type;

import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;

import studios.milkdromeda.octo.mod.BytecodeIndex;
import studios.milkdromeda.octo.mod.BytecodeIndex.ScannedAnnotation;
import studios.milkdromeda.octo.mod.BytecodeIndex.ScannedClass;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * What a NeoForge mod reads to find the classes it is meant to instantiate.
 *
 * <p>NeoForge mods do not declare their plugin classes anywhere a human can
 * read: they annotate them and ask the loader afterwards. JEI's whole content —
 * every recipe category, every ingredient type — comes from the classes carrying
 * {@code @JeiPlugin}, found through {@code ModList.get().getAllScanData()}. With
 * nothing to find, JEI starts, finds no plugins and dies in its own constructor;
 * that is the {@code allScanData is null} in the crash this was written for.
 *
 * <p>Every mod is offered, not only the NeoForge ones, which is the same
 * decision {@link ModInfos} makes and for the same reason: under Octo there is
 * one mod list. A NeoForge mod looking for classes annotated with its own
 * annotation finds them in a Fabric mod's jar too, which is the only answer that
 * is actually true.
 */
public final class ScanData {
    private static final OctoLog LOG = OctoLog.of(ScanData.class);

    /**
     * Built on the first question and kept, because it is asked once per mod that
     * has plugins to find and the conversion walks every class in every jar.
     *
     * <p>Keyed by the loaded mod itself rather than its id, so that a second
     * launch in one JVM — which is every test after the first — cannot be handed
     * the classes of the mod that had that id last time.
     */
    private static final Map<LoadedMod, ModFileScanData> BY_MOD = new ConcurrentHashMap<>();

    private ScanData() {
    }

    /** @return one entry per loaded mod, in load order */
    public static List<ModFileScanData> all() {
        if (!OctoRuntime.isRunning()) {
            return List.of();
        }

        List<ModFileScanData> out = new ArrayList<>();

        for (LoadedMod mod : OctoRuntime.get().loadedMods()) {
            out.add(of(mod));
        }

        return out;
    }

    /** Test hook: forgets what was converted, so a fresh launch converts again. */
    public static void reset() {
        BY_MOD.clear();
    }

    private static ModFileScanData of(LoadedMod mod) {
        return BY_MOD.computeIfAbsent(mod, ScanData::convert);
    }

    private static ModFileScanData convert(LoadedMod mod) {
        BytecodeIndex index = mod.candidate().bytecode();
        ModFileScanData data = new ModFileScanData();

        for (ScannedClass scanned : index.classes()) {
            data.getClasses().add(new ModFileScanData.ClassData(
                    Type.getObjectType(scanned.internalName()),
                    scanned.superName() == null ? null : Type.getObjectType(scanned.superName()),
                    new LinkedHashSet<>(scanned.interfaces().stream().map(Type::getObjectType).toList())));
        }

        for (ScannedAnnotation scanned : index.annotations()) {
            data.getAnnotations().add(new ModFileScanData.AnnotationData(
                    Type.getType(scanned.descriptor()),
                    targetOf(scanned.target()),
                    Type.getObjectType(scanned.className()),
                    scanned.member(),
                    scanned.values()));
        }

        // Mods that walk from scan data back to "which file was this" — to name
        // the mod in their own error messages, mostly — read it from here. Never
        // a null entry: a mod iterating this list would find it there rather than
        // here, in the middle of reporting some other problem.
        ModFileInfo file = ModFileInfo.of(mod.id());

        if (file != null) {
            data.addModFileInfo(file);
        }
        LOG.debug("{}: {} class(es) and {} annotation(s) available to mods that look for them",
                mod.id(), index.classes().size(), index.annotations().size());
        return data;
    }

    private static ElementType targetOf(ScannedAnnotation.Target target) {
        return switch (target) {
            case FIELD -> ElementType.FIELD;
            case METHOD -> ElementType.METHOD;
            case TYPE -> ElementType.TYPE;
        };
    }
}
