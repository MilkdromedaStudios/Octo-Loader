package studios.milkdromeda.octo.launcher.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.launcher.game.Net;

/**
 * A player's Minecraft profile: their skin and the capes they own, changed
 * through Minecraft's own profile service with their session, as the official
 * launcher and minecraft.net do.
 */
public final class MinecraftProfile {
    /** Where each call goes, so a test can stand in for the real service. */
    public record Endpoints(String profile, String names, String sessions) {
        public static final Endpoints LIVE = new Endpoints("https://api.minecraftservices.com/minecraft/profile",
                "https://api.mojang.com/users/profiles/minecraft/",
                "https://sessionserver.mojang.com/session/minecraft/profile/");
    }

    /** A skin: where its texture is, and whether it has slim arms. */
    public record Skin(String id, String url, boolean slim, boolean active) {
    }

    /** A cape the player owns. */
    public record Cape(String id, String alias, String url, boolean active) {
    }

    /** What the profile service says the player has now. */
    public record State(String id, String name, Skin skin, List<Cape> capes) {
        public Cape activeCape() {
            return capes.stream().filter(Cape::active).findFirst().orElse(null);
        }
    }

    private final Endpoints endpoints;

    public MinecraftProfile() {
        this(endpoints());
    }

    /**
     * Mojang's own, unless {@code -Docto.profile.base=http://host:port} names a
     * stand-in serving the same paths, for trying the Skins page.
     */
    private static Endpoints endpoints() {
        String base = System.getProperty("octo.profile.base");
        return base == null || base.isBlank() ? Endpoints.LIVE : new Endpoints(base + "/minecraft/profile",
                base + "/users/profiles/minecraft/", base + "/session/minecraft/profile/");
    }

    public MinecraftProfile(Endpoints endpoints) {
        this.endpoints = endpoints;
    }

    /** The skin and capes on the account now. */
    public State get(Account account) throws IOException {
        return parse(send(Net.request(endpoints.profile()).header("Authorization", "Bearer " + account.accessToken())
                .GET().build()));
    }

    /**
     * Uploads a skin. Minecraft takes a 64×64 PNG (or a legacy 64×32 one).
     *
     * @return the profile as it is afterwards
     */
    public State uploadSkin(Account account, byte[] png, boolean slim) throws IOException {
        String boundary = "octo-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
                + (slim ? "slim" : "classic") + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(png);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return parse(send(Net.request(endpoints.profile() + "/skins")
                .header("Authorization", "Bearer " + account.accessToken())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build()));
    }

    /** Puts the account back on the default skin. */
    public State resetSkin(Account account) throws IOException {
        send(Net.request(endpoints.profile() + "/skins/active").header("Authorization",
                "Bearer " + account.accessToken()).DELETE().build());
        return get(account);
    }

    /** Wears one of the capes the account owns. */
    public State showCape(Account account, String capeId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("capeId", capeId);
        return parse(send(Net.request(endpoints.profile() + "/capes/active")
                .header("Authorization", "Bearer " + account.accessToken()).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build()));
    }

    /** Takes the cape off. */
    public State hideCape(Account account) throws IOException {
        send(Net.request(endpoints.profile() + "/capes/active").header("Authorization",
                "Bearer " + account.accessToken()).DELETE().build());
        return get(account);
    }

    /**
     * Another player's skin, by their name: to try on, or keep in the library.
     *
     * @return the skin, or {@code null} when there is no such player
     */
    public Skin skinOf(String playerName) throws IOException {
        HttpResponse<String> found = sendRaw(Net.request(endpoints.names()
                + URLEncoder.encode(playerName.strip(), StandardCharsets.UTF_8)).GET().build());

        if (found.statusCode() == 404 || found.statusCode() == 204) {
            return null;
        }

        JsonObject player = object(found);
        String id = string(player, "id");
        JsonObject profile = object(sendRaw(Net.request(endpoints.sessions() + id).GET().build()));

        if (profile.get("properties") instanceof JsonArray properties) {
            for (JsonElement element : properties) {
                if (element instanceof JsonObject property && "textures".equals(string(property, "name"))) {
                    JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder()
                            .decode(string(property, "value")), StandardCharsets.UTF_8)).getAsJsonObject()
                            .getAsJsonObject("textures");
                    JsonObject skin = textures == null ? null : textures.getAsJsonObject("SKIN");

                    if (skin != null) {
                        boolean slim = skin.get("metadata") instanceof JsonObject metadata
                                && "slim".equals(string(metadata, "model"));
                        return new Skin(null, string(skin, "url"), slim, true);
                    }
                }
            }
        }

        return null;
    }

    // ---------------------------------------------------------------- HTTP

    private static HttpResponse<String> sendRaw(HttpRequest request) throws IOException {
        try {
            try {
                return Net.client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // A connection kept open from before can have gone stale (a network change,
                // a server restart): a request that changes nothing is sent once more.
                if (!request.method().equals("GET")) {
                    throw e;
                }

                return Net.client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cancelled", e);
        } catch (IOException e) {
            throw new IOException("Could not reach Minecraft's profile service. Check your internet connection and "
                    + "try again.", e);
        }
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException {
        HttpResponse<String> response = sendRaw(request);

        if (response.statusCode() / 100 != 2) {
            throw new IOException(explain(response));
        }

        return response;
    }

    static String explain(HttpResponse<String> response) {
        String message = null;

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            message = string(json, "errorMessage") != null ? string(json, "errorMessage") : string(json, "error");
        } catch (RuntimeException e) {
            // No JSON: the status says enough.
        }

        return switch (response.statusCode()) {
            case 400 -> "Minecraft did not take that" + (message == null ? "." : ": " + message)
                    + " A skin must be a 64×64 PNG.";
            case 401 -> "Your sign-in has run out. Sign in again and try once more.";
            case 429 -> "Too many changes at once. Wait a minute and try again.";
            default -> "Minecraft's profile service answered HTTP " + response.statusCode()
                    + (message == null ? "." : ": " + message);
        };
    }

    private static JsonObject object(HttpResponse<String> response) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException(explain(response));
        }

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    static State parse(HttpResponse<String> response) throws IOException {
        JsonObject json;

        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Minecraft's profile service answered with something unreadable");
        }

        Skin skin = null;

        if (json.get("skins") instanceof JsonArray skins) {
            for (JsonElement element : skins) {
                if (element instanceof JsonObject entry && "ACTIVE".equals(string(entry, "state"))) {
                    skin = new Skin(string(entry, "id"), string(entry, "url"),
                            "SLIM".equalsIgnoreCase(string(entry, "variant")), true);
                }
            }
        }

        List<Cape> capes = new ArrayList<>();

        if (json.get("capes") instanceof JsonArray list) {
            for (JsonElement element : list) {
                if (element instanceof JsonObject entry) {
                    capes.add(new Cape(string(entry, "id"), string(entry, "alias"), string(entry, "url"),
                            "ACTIVE".equals(string(entry, "state"))));
                }
            }
        }

        return new State(string(json, "id"), string(json, "name"), skin, capes);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
