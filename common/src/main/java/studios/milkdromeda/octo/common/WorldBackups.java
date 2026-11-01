package studios.milkdromeda.octo.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A world's backups: whole-world zip files kept beside the game's own
 * folders, in {@code <game>/backups/<world>/}, made and restored the same way
 * whether the loader takes one before a world opens or Octo Launcher takes one
 * when asked.
 *
 * <p>A backup is written to a {@code .part} file and renamed only once it is
 * complete, so a backup that exists is a whole one. A restore first backs up
 * the world it replaces, unpacks into a folder beside it, and only then swaps
 * the two, so a restore that fails half way leaves the world as it was.
 */
public final class WorldBackups {
    /** Why a backup was made, as written into its file name. */
    public static final String MANUAL = "manual";
    public static final String MODS_CHANGED = "mods-changed";
    public static final String MISSING_CONTENT = "missing-content";
    public static final String BEFORE_RESTORE = "before-restore";
    public static final String BEFORE_LAUNCH = "before-launch";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final String INFO_ENTRY = "octo-backup.txt";

    /** A backup on disk. */
    public record Backup(Path file, String world, Instant time, String reason, long size) {
        public boolean isAutomatic() {
            return !reason.equals(MANUAL);
        }
    }

    private WorldBackups() {
    }

    /**
     * The folder a world's backups go in: {@code <game>/backups/<world>} for a
     * world in a client's {@code saves}, {@code <server>/backups/<world>} for a
     * server's.
     */
    public static Path folderFor(Path world) {
        Path normalized = world.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Path root = parent != null && parent.getFileName() != null
                && parent.getFileName().toString().equals("saves") && parent.getParent() != null
                        ? parent.getParent() : parent;
        return root.resolve("backups").resolve(normalized.getFileName().toString());
    }

    /** Backs a world up, the whole folder but its session lock, into {@link #folderFor(Path)}. */
    public static Backup create(Path world, String reason) throws IOException {
        return create(world, folderFor(world), reason);
    }

    public static Backup create(Path world, Path folder, String reason) throws IOException {
        if (!Files.isDirectory(world)) {
            throw new IOException(world + " is not a world folder");
        }

        String name = world.getFileName().toString();
        LocalDateTime now = LocalDateTime.now();
        String safeReason = reason.replaceAll("[^A-Za-z0-9-]", "-");
        Files.createDirectories(folder);
        Path target = folder.resolve(name + "_" + STAMP.format(now) + "_" + safeReason + ".zip");

        for (int n = 2; Files.exists(target); n++) {
            target = folder.resolve(name + "_" + STAMP.format(now) + "-" + n + "_" + safeReason + ".zip");
        }

        Path part = target.resolveSibling(target.getFileName() + ".part");

        try (OutputStream out = Files.newOutputStream(part); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setLevel(5);
            ZipEntry info = new ZipEntry(INFO_ENTRY);
            zip.putNextEntry(info);
            zip.write(("world=" + name + "\nreason=" + reason + "\ntime=" + Instant.now() + "\nfrom="
                    + world.toAbsolutePath().normalize() + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            Files.walkFileTree(world, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(world)) {
                        zip.putNextEntry(new ZipEntry(entryName(world, dir) + "/"));
                        zip.closeEntry();
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // The running game holds this one locked, and it means
                    // nothing outside the session that made it.
                    if (file.getFileName().toString().equals("session.lock")) {
                        return FileVisitResult.CONTINUE;
                    }

                    ZipEntry entry = new ZipEntry(entryName(world, file));
                    entry.setTime(attrs.lastModifiedTime().toMillis());
                    zip.putNextEntry(entry);
                    Files.copy(file, zip);
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                    throw new IOException("could not read " + file + " for the backup", e);
                }
            });
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }

        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        return parse(target).orElseThrow();
    }

    private static String entryName(Path world, Path file) {
        return world.relativize(file).toString().replace('\\', '/');
    }

    /** A world's backups, newest first. */
    public static List<Backup> list(Path folder) {
        List<Backup> out = new ArrayList<>();

        if (!Files.isDirectory(folder)) {
            return out;
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.zip")) {
            for (Path file : files) {
                parse(file).ifPresent(out::add);
            }
        } catch (IOException e) {
            return out;
        }

        out.sort(Comparator.comparing(Backup::time).reversed());
        return out;
    }

    /** {@code <world>_<yyyy-MM-dd_HH-mm-ss>[-n]_<reason>.zip}, as {@link #create} names them. */
    static java.util.Optional<Backup> parse(Path file) {
        String name = file.getFileName().toString();

        if (!name.endsWith(".zip")) {
            return java.util.Optional.empty();
        }

        String base = name.substring(0, name.length() - 4);
        int reasonAt = base.lastIndexOf('_');

        if (reasonAt < 0) {
            return java.util.Optional.empty();
        }

        String reason = base.substring(reasonAt + 1);
        String rest = base.substring(0, reasonAt);
        // The stamp is the last 19 characters before the reason, perhaps with "-n" after it.
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(.*)_(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})(-\\d+)?$").matcher(rest);

        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }

        try {
            Instant time = LocalDateTime.parse(matcher.group(2), STAMP).atZone(ZoneId.systemDefault()).toInstant();
            return java.util.Optional.of(new Backup(file, matcher.group(1), time, reason, Files.size(file)));
        } catch (IOException | RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Puts a backup back in place of the world. The world as it is now is
     * backed up first, unless there is none.
     *
     * @return the backup made of the world it replaced, or {@code null}
     */
    public static Backup restore(Backup backup, Path world) throws IOException {
        Path normalized = world.toAbsolutePath().normalize();
        Backup previous = null;

        if (Files.isDirectory(normalized)) {
            if (Files.exists(normalized.resolve("session.lock")) && isLocked(normalized.resolve("session.lock"))) {
                throw new IOException("the world is open in the game; close it before restoring");
            }

            previous = create(normalized, folderFor(normalized), BEFORE_RESTORE);
        }

        Path staging = normalized.resolveSibling(normalized.getFileName() + ".octo-restoring");
        deleteTree(staging);
        Files.createDirectories(staging);

        try (InputStream in = Files.newInputStream(backup.file()); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().equals(INFO_ENTRY)) {
                    continue;
                }

                Path target = staging.resolve(entry.getName()).normalize();

                // Nothing a zip names may land outside the world.
                if (!target.startsWith(staging)) {
                    throw new IOException("the backup names a file outside the world: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException | RuntimeException e) {
            deleteTree(staging);
            throw e;
        }

        Path old = normalized.resolveSibling(normalized.getFileName() + ".octo-replaced");
        deleteTree(old);

        if (Files.exists(normalized)) {
            Files.move(normalized, old);
        }

        try {
            Files.move(staging, normalized);
        } catch (IOException e) {
            // Put the world back as it was.
            if (Files.exists(old)) {
                Files.move(old, normalized);
            }

            throw e;
        }

        deleteTree(old);
        return previous;
    }

    /** Removes all but the newest {@code keep} automatic backups; manual ones are the player's to remove. */
    public static List<Backup> prune(Path folder, int keep) throws IOException {
        List<Backup> removed = new ArrayList<>();
        int kept = 0;

        for (Backup backup : list(folder)) {
            if (!backup.isAutomatic()) {
                continue;
            }

            if (kept++ >= keep) {
                Files.deleteIfExists(backup.file());
                removed.add(backup);
            }
        }

        return removed;
    }

    private static boolean isLocked(Path lock) {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lock,
                java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock held = channel.tryLock();

            if (held == null) {
                return true;
            }

            held.release();
            return false;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
