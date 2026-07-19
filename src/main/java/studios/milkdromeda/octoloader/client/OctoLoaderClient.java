package studios.milkdromeda.octoloader.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import studios.milkdromeda.octoloader.OctoRuntime;
import studios.milkdromeda.octoloader.report.CompatReport;
import studios.milkdromeda.octoloader.report.CompatStatus;
import studios.milkdromeda.octoloader.report.ModReportEntry;

/**
 * Client-side surface of the compatibility report: once the client has
 * started, a toast summarizes what Octo Loader did — mods translated and
 * running, mods awaiting a restart, and mods it had to skip — pointing at
 * {@code octo-report.md} for the full table.
 */
public class OctoLoaderClient implements ClientModInitializer {
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(10_000L);

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            CompatReport report = OctoRuntime.currentReport();
            if (report == null) return;

            int active = 0;
            int awaitingRestart = 0;
            int skipped = 0;
            for (ModReportEntry entry : report.entries()) {
                switch (entry.status()) {
                    case NATIVE -> {
                    }
                    case TRANSLATED -> {
                        if (entry.reason() != null && entry.reason().contains("restart")) awaitingRestart++;
                        else active++;
                    }
                    default -> skipped++;
                }
            }
            if (awaitingRestart == 0 && skipped == 0 && active == 0) return;

            String summary;
            if (awaitingRestart > 0) {
                summary = awaitingRestart + " mod(s) translated — restart to activate";
            } else if (active > 0 && skipped == 0) {
                summary = active + " translated mod(s) running";
            } else {
                summary = skipped + " mod(s) skipped — see octo-report.md";
            }
            SystemToast.add(client.gui.toastManager(), TOAST_ID,
                    Component.literal("Octo Loader"), Component.literal(summary));
        });
    }
}
