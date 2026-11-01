package studios.milkdromeda.octo.launcher.auth;

import java.time.Instant;

/**
 * A signed-in Minecraft account: its profile, its current session, and the
 * Microsoft refresh token that gets a new session without signing in again.
 *
 * @param id the Minecraft profile's UUID, without dashes
 * @param accessToken the Minecraft session, good until {@code expiresAt}
 * @param expiresAt seconds since the epoch
 */
public record Account(String id, String name, String skinUrl, String accessToken, long expiresAt,
        String refreshToken) {
    /** Whether the session is still good for a while (a game started now keeps it). */
    public boolean fresh() {
        return accessToken != null && !accessToken.isBlank()
                && Instant.now().getEpochSecond() < expiresAt - 30 * 60;
    }

    /** The UUID with its dashes, as Minecraft writes it. */
    public String dashedId() {
        if (id == null || id.length() != 32) {
            return id;
        }

        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-"
                + id.substring(16, 20) + "-" + id.substring(20);
    }
}
