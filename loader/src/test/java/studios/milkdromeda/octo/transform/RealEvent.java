package studios.milkdromeda.octo.transform;

/** Test fixture: a class the runtime has, standing for NeoForge's Event, that a stand-in must extend. */
public abstract class RealEvent {
    private boolean canceled;

    protected RealEvent(String name) {
    }

    public final boolean isCanceled() {
        return canceled;
    }

    public final void setCanceled(boolean value) {
        canceled = value;
    }

    /** What the mod posts its event to: it has to be a RealEvent. */
    public static boolean post(RealEvent event) {
        event.setCanceled(true);
        return event.isCanceled();
    }
}
