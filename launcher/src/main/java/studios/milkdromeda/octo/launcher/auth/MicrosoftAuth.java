package studios.milkdromeda.octo.launcher.auth;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.launcher.game.Net;

/**
 * Signing in to Minecraft with a Microsoft account, the way Minecraft itself
 * does: Microsoft's sign-in gives an Xbox Live token, Xbox Live a token for
 * Minecraft's services, and those a Minecraft session. The password is typed
 * on Microsoft's own page, in the browser; Octo never sees it.
 *
 * <p>Sign-in uses the device code flow: Octo shows a short code, the player
 * enters it at microsoft.com/link and signs in there.
 */
public final class MicrosoftAuth {
    /** Where each step is, so a test can stand in for the real services. */
    public record Endpoints(String deviceCode, String token, String xbox, String xsts, String minecraftLogin,
            String profile, String entitlements) {
        public static final Endpoints LIVE = new Endpoints(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                "https://user.auth.xboxlive.com/user/authenticate",
                "https://xsts.auth.xboxlive.com/xsts/authorize",
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                "https://api.minecraftservices.com/minecraft/profile",
                "https://api.minecraftservices.com/entitlements/mcstore");
    }

    /** The code the player enters, and where. */
    public record DeviceCode(String userCode, String deviceCode, String verificationUri, int interval, int expiresIn) {
        /** The sign-in page with the code already filled in. */
        public String linkWithCode() {
            return verificationUri + (verificationUri.contains("?") ? "&" : "?") + "otc="
                    + URLEncoder.encode(userCode, StandardCharsets.UTF_8);
        }
    }

    /** A sign-in that did not work, said so a player can do something about it. */
    public static final class SignInException extends IOException {
        private static final long serialVersionUID = 1L;

        public SignInException(String message) {
            super(message);
        }
    }

    static final String SCOPE = "XboxLive.signin offline_access";

    private final String clientId;
    private final Endpoints endpoints;

    public MicrosoftAuth(String clientId) {
        this(clientId, endpoints());
    }

    /**
     * Microsoft's own, unless {@code -Docto.auth.base=http://host:port} names
     * a stand-in serving the same steps under one address, for trying the
     * sign-in screens.
     */
    private static Endpoints endpoints() {
        String base = System.getProperty("octo.auth.base");

        if (base == null || base.isBlank()) {
            return Endpoints.LIVE;
        }

        return new Endpoints(base + "/devicecode", base + "/token", base + "/xbox", base + "/xsts", base + "/login",
                base + "/profile", base + "/entitlements");
    }

    public MicrosoftAuth(String clientId, Endpoints endpoints) {
        this.clientId = clientId;
        this.endpoints = endpoints;
    }

    /** Asks Microsoft for a code for the player to enter. */
    public DeviceCode start() throws IOException {
        JsonObject response = form(endpoints.deviceCode(), Map.of("client_id", clientId, "scope", SCOPE));
        return new DeviceCode(string(response, "user_code"), string(response, "device_code"),
                string(response, "verification_uri"), integer(response, "interval", 5),
                integer(response, "expires_in", 900));
    }

    /**
     * Waits for the player to sign in with the code, then signs in to
     * Minecraft.
     *
     * @param cancelled checked between polls; true stops waiting
     */
    public Account finish(DeviceCode code, BooleanSupplier cancelled) throws IOException {
        long deadline = System.currentTimeMillis() + code.expiresIn() * 1000L;
        long interval = Math.max(1, code.interval()) * 1000L;

        while (System.currentTimeMillis() < deadline) {
            sleep(interval, cancelled);

            if (cancelled.getAsBoolean()) {
                throw new SignInException("Sign-in was cancelled.");
            }

            JsonObject response = formAllowingErrors(endpoints.token(), Map.of(
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", clientId,
                    "device_code", code.deviceCode()));
            String error = string(response, "error");

            if (error == null) {
                return minecraft(string(response, "access_token"), string(response, "refresh_token"));
            }

            switch (error) {
                case "authorization_pending" -> {
                    // Not yet.
                }
                case "slow_down" -> interval += 5000;
                case "authorization_declined" -> throw new SignInException("Sign-in was declined on Microsoft's "
                        + "page.");
                case "expired_token", "bad_verification_code" -> throw new SignInException("The code ran out "
                        + "before sign-in finished. Try again.");
                default -> throw new SignInException(describe(response));
            }
        }

        throw new SignInException("The code ran out before sign-in finished. Try again.");
    }

    /** A fresh Minecraft session from the account's Microsoft refresh token. */
    public Account refresh(Account account) throws IOException {
        if (account.refreshToken() == null || account.refreshToken().isBlank()) {
            throw new SignInException("Sign in again: this account has no saved sign-in.");
        }

        JsonObject response = formAllowingErrors(endpoints.token(), Map.of("grant_type", "refresh_token",
                "client_id", clientId, "refresh_token", account.refreshToken(), "scope", SCOPE));

        if (string(response, "error") != null) {
            throw new SignInException("Your sign-in for " + account.name() + " has run out. Sign in again. ("
                    + describe(response) + ")");
        }

        return minecraft(string(response, "access_token"), string(response, "refresh_token"));
    }

    /** From a Microsoft token to a Minecraft session, through Xbox Live, and the profile that goes with it. */
    Account minecraft(String microsoftToken, String refreshToken) throws IOException {
        // Xbox Live.
        JsonObject xboxRequest = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + microsoftToken);
        xboxRequest.add("Properties", properties);
        xboxRequest.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xboxRequest.addProperty("TokenType", "JWT");
        JsonObject xbox = post(endpoints.xbox(), xboxRequest, null, "Xbox Live");
        String xboxToken = string(xbox, "Token");
        String userHash = userHash(xbox);

        // A token for Minecraft's services.
        JsonObject xstsRequest = new JsonObject();
        JsonObject xstsProperties = new JsonObject();
        xstsProperties.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xboxToken);
        xstsProperties.add("UserTokens", userTokens);
        xstsRequest.add("Properties", xstsProperties);
        xstsRequest.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsRequest.addProperty("TokenType", "JWT");
        JsonObject xsts = post(endpoints.xsts(), xstsRequest, null, "Xbox Live");
        String xstsToken = string(xsts, "Token");

        // Minecraft.
        JsonObject login = new JsonObject();
        login.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        JsonObject session = post(endpoints.minecraftLogin(), login, null, "Minecraft");
        String accessToken = string(session, "access_token");
        long expires = Instant.now().getEpochSecond() + integer(session, "expires_in", 86400);

        // Who they are in Minecraft, which is also whether they have it.
        JsonObject profile = get(endpoints.profile(), accessToken);

        if (profile == null) {
            JsonObject entitlements = get(endpoints.entitlements(), accessToken);

            if (owns(entitlements)) {
                throw new SignInException("You own Minecraft but have not chosen a name yet. Open minecraft.net, "
                        + "sign in, set your profile name, then sign in here again.");
            }

            throw new SignInException("This Microsoft account does not own Minecraft: Java Edition. Buy it at "
                    + "minecraft.net, or sign in with the account that has it.");
        }

        return new Account(string(profile, "id"), string(profile, "name"), skin(profile), accessToken, expires,
                refreshToken);
    }

    static boolean owns(JsonObject entitlements) {
        if (entitlements == null || !(entitlements.get("items") instanceof JsonArray items)) {
            return false;
        }

        for (JsonElement item : items) {
            String name = item instanceof JsonObject object ? string(object, "name") : null;

            if ("game_minecraft".equals(name) || "product_minecraft".equals(name)) {
                return true;
            }
        }

        return false;
    }

    private static String skin(JsonObject profile) {
        if (profile.get("skins") instanceof JsonArray skins) {
            for (JsonElement element : skins) {
                if (element instanceof JsonObject skin && "ACTIVE".equals(string(skin, "state"))) {
                    return string(skin, "url");
                }
            }
        }

        return null;
    }

    private static String userHash(JsonObject xbox) throws SignInException {
        if (xbox.get("DisplayClaims") instanceof JsonObject claims && claims.get("xui") instanceof JsonArray xui
                && !xui.isEmpty() && xui.get(0) instanceof JsonObject first && string(first, "uhs") != null) {
            return string(first, "uhs");
        }

        throw new SignInException("Xbox Live answered without a user. Try again.");
    }

    // ------------------------------------------------------------- HTTP

    private JsonObject form(String url, Map<String, String> fields) throws IOException {
        JsonObject response = formAllowingErrors(url, fields);

        if (string(response, "error") != null) {
            throw new SignInException(describe(response));
        }

        return response;
    }

    private JsonObject formAllowingErrors(String url, Map<String, String> fields) throws IOException {
        StringJoiner body = new StringJoiner("&");
        fields.forEach((key, value) -> body.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        HttpResponse<String> response = send(Net.request(url).header("Content-Type",
                "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        JsonObject json = parse(response.body());

        if (json == null) {
            throw new SignInException("Microsoft's sign-in answered HTTP " + response.statusCode() + ". Try again "
                    + "later.");
        }

        return json;
    }

    private static JsonObject post(String url, JsonObject body, String bearer, String service) throws IOException {
        HttpRequest.Builder request = Net.request(url).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));

        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }

        HttpResponse<String> response = send(request.build());
        JsonObject json = parse(response.body());

        if (response.statusCode() / 100 != 2 || json == null) {
            throw new SignInException(failure(service, response.statusCode(), json));
        }

        return json;
    }

    /** A GET with the Minecraft token; {@code null} for a 404. */
    private static JsonObject get(String url, String bearer) throws IOException {
        HttpResponse<String> response = send(Net.request(url).header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json").GET().build());

        if (response.statusCode() == 404) {
            return null;
        }

        JsonObject json = parse(response.body());

        if (response.statusCode() != 200 || json == null) {
            throw new SignInException(failure("Minecraft", response.statusCode(), json));
        }

        return json;
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return Net.client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SignInException("Sign-in was cancelled.");
        } catch (IOException e) {
            throw new SignInException("Could not reach " + request.uri().getHost() + ". Check your internet "
                    + "connection. (" + e.getMessage() + ")");
        }
    }

    /** What went wrong, in words, from a service's error answer. */
    static String failure(String service, int status, JsonObject json) {
        if (json != null && json.get("XErr") != null) {
            return switch (json.get("XErr").getAsString()) {
                case "2148916227" -> "This account is banned from Xbox Live.";
                case "2148916229" -> "Xbox Live is restricted on this account. A parent can allow it in the "
                        + "Microsoft Family settings.";
                case "2148916233" -> "This Microsoft account has no Xbox profile yet. Sign in once at xbox.com to "
                        + "make one, then try again.";
                case "2148916234" -> "Accept the Xbox terms first: sign in once at xbox.com, then try again.";
                case "2148916235" -> "Xbox Live is not available in your country or region.";
                case "2148916236", "2148916237" -> "This account needs adult verification on xbox.com first.";
                case "2148916238" -> "This account is under 18 and must be added to a Microsoft Family by an "
                        + "adult first.";
                default -> service + " refused the sign-in (error " + json.get("XErr").getAsString() + ").";
            };
        }

        String message = json == null ? null
                : string(json, "errorMessage") != null ? string(json, "errorMessage") : string(json, "error");

        if (status == 403 && message != null && message.toLowerCase(java.util.Locale.ROOT).contains("app")) {
            return "Minecraft has not approved this build of Octo Launcher for sign-in yet (" + message + "). Use "
                    + "the Minecraft Launcher to play in the meantime.";
        }

        if (status == 429) {
            return service + " is getting too many sign-ins right now. Wait a minute and try again.";
        }

        return service + " answered HTTP " + status + (message == null ? "" : ": " + message) + ".";
    }

    private static String describe(JsonObject error) {
        String code = string(error, "error");
        String description = string(error, "error_description");

        if ("unauthorized_client".equals(code) || "invalid_client".equals(code)) {
            return "Microsoft does not know this build's app registration (" + code + "). Set its client ID in "
                    + "Settings, or use the Minecraft Launcher.";
        }

        return description != null ? description.split("\\r?\\n")[0] : "Microsoft's sign-in said: " + code;
    }

    private static JsonObject parse(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "" : body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static void sleep(long millis, BooleanSupplier cancelled) throws SignInException {
        long end = System.currentTimeMillis() + millis;

        try {
            while (System.currentTimeMillis() < end && !cancelled.getAsBoolean()) {
                Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SignInException("Sign-in was cancelled.");
        }
    }
}
