package studios.milkdromeda.octo.transform;

/**
 * Lets each entry a NeoForge mod registers fail on its own.
 *
 * <p>A {@code DeferredRegister} puts all of a mod's entries for one registry
 * in from one listener, through {@code RegisterEvent.register}. A block from
 * another version whose constructor fails ended that listener, and every
 * block after it in the mod went unregistered with it. Each call is guarded:
 * the entry that fails is reported and left out, and the rest go in.
 */
public final class RegistrationEntryGuard implements Transformer {
    private static final String REGISTER_EVENT = "net/neoforged/neoforge/registries/RegisterEvent";
    private static final String REGISTER = "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/Identifier;"
            + "Ljava/util/function/Supplier;)V";

    @Override
    public String name() {
        return "registration-entry-guard";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(REGISTER_EVENT);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        return CallbackGuard.guardMethod(bytes, className, "register", REGISTER);
    }
}
