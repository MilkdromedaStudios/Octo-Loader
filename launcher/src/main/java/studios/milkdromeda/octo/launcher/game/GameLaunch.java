package studios.milkdromeda.octo.launcher.game;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The command that starts the game, built from the version's own description of it. */
public final class GameLaunch {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");

    /** Who is playing: the signed-in Minecraft profile and its session. */
    public record Player(String name, String uuid, String accessToken, String xuid) {
    }

    /** What the player set for the game. */
    public record Options(Path java, int memoryMb, List<String> extraJvmArguments, int width, int height,
            String launcherName, String launcherVersion) {
    }

    private GameLaunch() {
    }

    /** The command line: Java, its options, the main class and the game's arguments. */
    public static List<String> command(Path gameDirectory, VersionProfile profile, Rules.Platform platform,
            Player player, Options options, Path natives) {
        Map<String, Boolean> features = new HashMap<>();
        features.put("has_custom_resolution", options.width() > 0 && options.height() > 0);
        Map<String, String> values = values(gameDirectory, profile, platform, player, options, natives);
        List<String> command = new ArrayList<>();
        command.add(options.java().toString());

        // Memory: the player's, in place of the game's suggestion.
        if (options.memoryMb() > 0) {
            command.add("-Xms" + Math.min(1024, options.memoryMb()) + "M");
            command.add("-Xmx" + options.memoryMb() + "M");
        }

        for (String option : expand(profile.defaultJvmArguments, platform, features, values)) {
            if (options.memoryMb() > 0 && (option.startsWith("-Xmx") || option.startsWith("-Xms"))) {
                continue;
            }

            command.add(option);
        }

        if (profile.defaultJvmArguments.isEmpty() && options.memoryMb() <= 0) {
            command.add("-Xmx2G");
        }

        command.addAll(options.extraJvmArguments());

        if (!profile.jvmArguments.isEmpty()) {
            command.addAll(expand(profile.jvmArguments, platform, features, values));
        } else {
            // Before 1.13 the version says nothing of Java's options.
            if (platform.os().equals("osx")) {
                command.add("-Xdock:name=Minecraft");
            }

            command.add("-Djava.library.path=" + values.get("natives_directory"));
            command.add("-cp");
            command.add(values.get("classpath"));
        }

        if (profile.logging != null && profile.logging.get("client") instanceof JsonObject client
                && Rules.string(client, "argument") != null && client.get("file") instanceof JsonObject) {
            command.add(Rules.string(client, "argument").replace("${path}",
                    GameFiles.logConfig(gameDirectory, profile).toString()));
        }

        command.add(profile.mainClass);
        List<String> game;

        if (!profile.gameArguments.isEmpty()) {
            game = expand(profile.gameArguments, platform, features, values);
        } else {
            game = new ArrayList<>();

            for (String part : String.valueOf(profile.legacyArguments).trim().split("\\s+")) {
                game.add(substitute(part, values));
            }
        }

        command.addAll(tidy(game));
        return command;
    }

    /** The values the version's arguments name. */
    static Map<String, String> values(Path gameDirectory, VersionProfile profile, Rules.Platform platform,
            Player player, Options options, Path natives) {
        Path libraries = gameDirectory.resolve("libraries");
        List<String> classpath = new ArrayList<>();

        for (Libraries.Artifact artifact : Libraries.classpath(profile, libraries, platform)) {
            classpath.add(artifact.path().toAbsolutePath().toString());
        }

        classpath.add(profile.jar(gameDirectory).toAbsolutePath().toString());
        Path assets = gameDirectory.resolve("assets");
        Map<String, String> values = new HashMap<>();
        values.put("auth_player_name", player.name());
        values.put("version_name", profile.id);
        values.put("game_directory", gameDirectory.toAbsolutePath().toString());
        values.put("assets_root", assets.toAbsolutePath().toString());
        values.put("game_assets", ("legacy".equals(profile.assets) || "pre-1.6".equals(profile.assets)
                ? GameFiles.virtualAssets(gameDirectory, profile) : assets).toAbsolutePath().toString());
        values.put("assets_index_name", profile.assets);
        values.put("auth_uuid", player.uuid());
        values.put("auth_access_token", player.accessToken());
        values.put("auth_session", "token:" + player.accessToken() + ":" + player.uuid());
        values.put("auth_xuid", player.xuid() == null ? "" : player.xuid());
        values.put("clientid", "");
        values.put("user_type", "msa");
        values.put("user_properties", "{}");
        values.put("version_type", profile.type);
        values.put("natives_directory", natives.toAbsolutePath().toString());
        values.put("launcher_name", options.launcherName());
        values.put("launcher_version", options.launcherVersion());
        values.put("classpath", String.join(File.pathSeparator, classpath));
        values.put("classpath_separator", File.pathSeparator);
        values.put("library_directory", libraries.toAbsolutePath().toString());
        values.put("resolution_width", Integer.toString(options.width()));
        values.put("resolution_height", Integer.toString(options.height()));
        return values;
    }

    /** The arguments that apply here, their placeholders filled in. */
    static List<String> expand(List<JsonElement> arguments, Rules.Platform platform, Map<String, Boolean> features,
            Map<String, String> values) {
        List<String> out = new ArrayList<>();

        for (JsonElement argument : arguments) {
            if (argument.isJsonPrimitive()) {
                out.add(substitute(argument.getAsString(), values));
            } else if (argument instanceof JsonObject conditional
                    && Rules.allows(conditional.get("rules"), platform, features)) {
                JsonElement value = conditional.get("value");

                if (value instanceof JsonArray list) {
                    for (JsonElement part : list) {
                        out.add(substitute(part.getAsString(), values));
                    }
                } else if (value != null && value.isJsonPrimitive()) {
                    out.add(substitute(value.getAsString(), values));
                }
            }
        }

        return out;
    }

    static String substitute(String text, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }

        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The game's arguments with an option given twice the same way said once
     * (Octo's profile repeats {@code --gameDir} for launchers that leave it
     * out), and an option with nothing to say left out.
     */
    static List<String> tidy(List<String> arguments) {
        List<String> out = new ArrayList<>();
        Map<String, String> given = new HashMap<>();

        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            boolean option = argument.startsWith("--");
            String value = i + 1 < arguments.size() ? arguments.get(i + 1) : null;

            if (option && value != null && !value.startsWith("--")) {
                i++;

                if (value.isEmpty() || value.equals(given.get(argument))) {
                    continue;
                }

                given.put(argument, value);
                out.add(argument);
                out.add(value);
            } else {
                out.add(argument);
            }
        }

        return out;
    }

    /** The command with the session token hidden, for a log a player might share. */
    public static String printable(List<String> command, String token) {
        StringBuilder out = new StringBuilder();

        for (String part : command) {
            out.append(token != null && !token.isEmpty() ? part.replace(token, "<hidden>") : part).append(' ');
        }

        return out.toString().trim();
    }

    /**
     * Starts the game in its folder, its output going to {@code log}.
     */
    public static Process start(List<String> command, Path gameDirectory, Path log) throws IOException {
        Files.createDirectories(log.getParent());
        return new ProcessBuilder(command).directory(gameDirectory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
    }
}
