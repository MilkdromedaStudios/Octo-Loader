package net.neoforged.fml.loading.modscan;

/**
 * The one part of NeoForge's annotation reader that reaches a mod.
 *
 * <p>Everything else about how a jar is scanned is NeoForge's own business and
 * Octo does it differently. But the values in the table that scanning produces
 * are handed to mods, and an enum-valued annotation member is not stored as the
 * enum — the scan never loads a class — it is stored as this pair of descriptor
 * and constant name. So a mod reading such a member casts to this type, which
 * means it has to be this type.
 */
public final class ModAnnotation {
    private ModAnnotation() {
    }

    /**
     * An enum constant named in an annotation, before anything has loaded it.
     *
     * @param desc  the enum type's descriptor, e.g. {@code Lnet/neoforged/api/distmarker/Dist;}
     * @param value the constant's name, e.g. {@code CLIENT}
     */
    public record EnumHolder(String desc, String value) {
    }
}
