package net.minecraftforge.eventbus.api;

/** Base class for everything posted on a Forge event bus. */
public abstract class Event {
    private boolean canceled;
    private Result result = Result.DEFAULT;

    public enum Result { DENY, DEFAULT, ALLOW }

    public boolean isCancelable() {
        return false;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean cancel) {
        if (!isCancelable()) {
            throw new UnsupportedOperationException(getClass().getName() + " is not cancelable");
        }

        this.canceled = cancel;
    }

    public boolean hasResult() {
        return false;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result value) {
        this.result = value;
    }
}
