package studios.milkdromeda.octo.launcher.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * The accounts signed in on this computer, kept in {@code accounts.json} in
 * Octo's own folder, readable by the player's user alone. Signing out removes
 * an account and its tokens from the file.
 */
public final class Accounts {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** What is written. */
    static final class Stored {
        String active;
        List<Account> accounts = new ArrayList<>();
    }

    private final Path file;
    private Stored stored;

    public Accounts(Path folder) {
        this.file = folder.resolve("accounts.json");
        this.stored = read(file);
    }

    private static Stored read(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                Stored read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);

                if (read != null && read.accounts != null) {
                    read.accounts.removeIf(account -> account == null || account.id() == null);
                    return read;
                }
            }
        } catch (IOException | JsonParseException e) {
            // Signed out, then.
        }

        return new Stored();
    }

    public synchronized List<Account> all() {
        return List.copyOf(stored.accounts);
    }

    /** The account Play uses, or {@code null} when no one is signed in. */
    public synchronized Account active() {
        for (Account account : stored.accounts) {
            if (account.id().equals(stored.active)) {
                return account;
            }
        }

        return stored.accounts.isEmpty() ? null : stored.accounts.get(0);
    }

    /** Adds an account, or replaces it with a fresher session, and makes it the one Play uses. */
    public synchronized void put(Account account) throws IOException {
        stored.accounts.removeIf(existing -> existing.id().equals(account.id()));
        stored.accounts.add(0, account);
        stored.active = account.id();
        save();
    }

    public synchronized void use(String id) throws IOException {
        stored.active = id;
        save();
    }

    /** Signs an account out: it and its tokens leave the file. */
    public synchronized void remove(String id) throws IOException {
        stored.accounts.removeIf(account -> account.id().equals(id));

        if (id.equals(stored.active)) {
            stored.active = stored.accounts.isEmpty() ? null : stored.accounts.get(0).id();
        }

        save();
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling("accounts.json.part");
        Files.deleteIfExists(part);
        Files.createFile(part);
        ownerOnly(part);
        Files.writeString(part, GSON.toJson(stored) + "\n", StandardCharsets.UTF_8);
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Readable and writable by the player's user and no one else, where the system can say so. */
    static void ownerOnly(Path file) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                return;
            }

            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);

            if (acl != null) {
                UserPrincipal owner = Files.getOwner(file);
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // The folder's own protection, then.
        }
    }
}
