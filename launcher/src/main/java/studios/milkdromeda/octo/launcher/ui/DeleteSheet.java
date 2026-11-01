package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import studios.milkdromeda.octo.launcher.LauncherSettings;
import studios.milkdromeda.octo.launcher.game.GameFolder;

/**
 * Deleting what is in the Minecraft folder, by kind: the downloaded game
 * (which comes back by itself), and, asked about twice, worlds and their
 * backups (which do not).
 */
final class DeleteSheet extends Sheet {
    private final Path game;
    private final Map<GameFolder.Kind, Widgets.Switch> switches = new EnumMap<>(GameFolder.Kind.class);
    private final Map<GameFolder.Kind, JLabel> sizes = new EnumMap<>(GameFolder.Kind.class);
    private final Widgets.Button delete = Widgets.button("Delete", Icons.Glyph.TRASH, Widgets.Kind.DESTRUCTIVE);
    private final Widgets.Field confirm = new Widgets.Field("Type DELETE to confirm", null);
    private final JPanel confirmRow = Widgets.stack(8);
    private Map<GameFolder.Kind, Long> measured = Map.of();

    private DeleteSheet(App app) {
        super(app, 640, 720);
        this.game = app.settings().gameDir();
        card.add(header("Delete Minecraft files", "From " + SettingsPage.tidy(game) + ". Choose what goes."),
                BorderLayout.NORTH);

        JPanel body = Widgets.stack(10);
        body.setBorder(BorderFactory.createEmptyBorder(12, 28, 8, 28));

        for (GameFolder.Kind kind : GameFolder.Kind.values()) {
            body.add(row(kind));
        }

        if (Files.isRegularFile(game.resolve("launcher_profiles.json"))) {
            body.add(Widgets.paragraph("This folder is shared with the Minecraft Launcher, which will download the "
                    + "game again too.", 12.5f, Style.FAINT, 0));
        }

        confirmRow.add(Widgets.paragraph("Worlds and backups cannot be downloaded again. Type DELETE to delete "
                + "them.", 13f, Style.AMBER, 0));
        confirmRow.add(confirm);
        confirmRow.setVisible(false);
        body.add(confirmRow);
        card.add(Widgets.page(body), BorderLayout.CENTER);

        JPanel foot = Widgets.clear(new BorderLayout());
        foot.setBorder(BorderFactory.createEmptyBorder(10, 28, 22, 28));
        JPanel actions = Widgets.row(10);
        Widgets.Button cancel = Widgets.button("Cancel", null, Widgets.Kind.SECONDARY);
        cancel.addActionListener(event -> close());
        actions.add(cancel);
        delete.addActionListener(event -> delete());
        actions.add(delete);
        foot.add(actions, BorderLayout.EAST);
        card.add(foot, BorderLayout.SOUTH);

        confirm.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                update();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                update();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                update();
            }
        });
    }

    static void show(App app) {
        DeleteSheet sheet = new DeleteSheet(app);
        sheet.open();
        sheet.update();
        app.run(() -> GameFolder.sizes(sheet.game, LauncherSettings.runtimes()), found -> {
            sheet.measured = found;

            for (Map.Entry<GameFolder.Kind, JLabel> size : sheet.sizes.entrySet()) {
                size.getValue().setText(bytes(found.getOrDefault(size.getKey(), 0L)));
            }

            sheet.update();
        });
    }

    private JComponent row(GameFolder.Kind kind) {
        Widgets.Card row = new Widgets.Card(Style.PANEL, 14, true);
        row.setLayout(new BorderLayout(14, 0));
        row.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        Widgets.Switch toggle = new Widgets.Switch(kind == GameFolder.Kind.GAME_FILES);
        toggle.onChange(value -> update());
        switches.put(kind, toggle);
        JPanel holder = Widgets.clear(new BorderLayout());
        holder.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        holder.add(toggle, BorderLayout.NORTH);
        row.add(holder, BorderLayout.WEST);
        JPanel text = Widgets.stack(3);
        text.add(Widgets.line(kind.title, Style.font(Font.BOLD, 14f), kind.irreplaceable ? Style.AMBER : Style.TEXT));
        text.add(Widgets.paragraph(kind.detail, 12.5f, Style.FAINT, 0));
        row.add(text, BorderLayout.CENTER);
        JLabel size = Widgets.text("...", 13f, Font.BOLD, Style.MUTED);
        sizes.put(kind, size);
        JPanel sizeHolder = Widgets.clear(new BorderLayout());
        sizeHolder.add(size, BorderLayout.NORTH);
        row.add(sizeHolder, BorderLayout.EAST);
        return row;
    }

    private Set<GameFolder.Kind> chosen() {
        Set<GameFolder.Kind> out = EnumSet.noneOf(GameFolder.Kind.class);
        switches.forEach((kind, toggle) -> {
            if (toggle.isOn()) {
                out.add(kind);
            }
        });
        return out;
    }

    private void update() {
        Set<GameFolder.Kind> chosen = chosen();
        boolean dangerous = chosen.stream().anyMatch(kind -> kind.irreplaceable);
        boolean appearing = dangerous && !confirmRow.isVisible();
        confirmRow.setVisible(dangerous);

        if (appearing) {
            // Into view, and ready to type in.
            javax.swing.SwingUtilities.invokeLater(() -> {
                confirmRow.scrollRectToVisible(new java.awt.Rectangle(0, 0, confirmRow.getWidth(),
                        confirmRow.getHeight()));
                confirm.requestFocusInWindow();
            });
        }
        long total = chosen.stream().mapToLong(kind -> measured.getOrDefault(kind, 0L)).sum();
        delete.setText(chosen.isEmpty() ? "Delete" : "Delete " + (measured.isEmpty() ? "" : bytes(total)));
        delete.setEnabled(!chosen.isEmpty() && (!dangerous
                || confirm.getText().strip().equalsIgnoreCase("delete")));
        card.revalidate();
        card.repaint();
    }

    private void delete() {
        Set<GameFolder.Kind> chosen = chosen();
        delete.setEnabled(false);
        delete.setText("Deleting...");
        app.run(() -> GameFolder.delete(game, chosen, LauncherSettings.runtimes()), freed -> {
            close();
            app.toast("Deleted " + bytes(freed) + ". " + (chosen.contains(GameFolder.Kind.GAME_FILES)
                    ? "Minecraft downloads again the next time you play." : ""), Style.GREEN);
            app.changed();
        }, failure -> update());
    }

    static String bytes(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }

        if (bytes >= 1L << 20) {
            return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
        }

        return bytes == 0 ? "Empty" : Math.max(1, bytes / 1024) + " KB";
    }
}
