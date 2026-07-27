package studios.milkdromeda.octo.mixin;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/**
 * Names {@link OctoMixinService} to mixin before mixin has loaded anything.
 *
 * <p>Mixin looks this up with {@link java.util.ServiceLoader} on its own class
 * loader, which is the one holding the loader jar, so the declaration lives in
 * {@code META-INF/services} alongside the service itself.
 */
public final class OctoMixinBootstrap implements IMixinServiceBootstrap {
    @Override
    public String getName() {
        return "Octo";
    }

    @Override
    public String getServiceClassName() {
        return OctoMixinService.class.getName();
    }

    @Override
    public void bootstrap() {
        // Nothing to prepare: Octo's class loader is already built by the time
        // mixin is asked to start, which is the whole reason mixin can be handed
        // a running game here instead of having to own the launch itself.
    }
}
