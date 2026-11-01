package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import studios.milkdromeda.octo.launcher.Play;
import studios.milkdromeda.octo.launcher.auth.Account;
import studios.milkdromeda.octo.launcher.auth.MicrosoftAuth;

/**
 * Signing in with Microsoft: a code to enter on Microsoft's page, which the
 * sheet copies and opens, and then it waits until sign-in finishes there.
 */
final class SignInSheet extends Sheet {
    private final JPanel body = Widgets.stack(16);
    private volatile boolean cancelled;
    private Consumer<Account> signedIn = account -> { };

    private SignInSheet(App app) {
        super(app, 560, 440);
        card.add(header("Sign in with Microsoft", "Use the Microsoft account you bought Minecraft with, or "
                + "the one with Xbox Game Pass."), BorderLayout.NORTH);
        body.setBorder(BorderFactory.createEmptyBorder(14, 28, 26, 28));
        card.add(body, BorderLayout.CENTER);
        onClose(() -> cancelled = true);
    }

    /** Opens the sheet; {@code then} runs once the account is signed in and saved. */
    static void show(App app, Consumer<Account> then) {
        SignInSheet sheet = new SignInSheet(app);
        sheet.signedIn = then;
        sheet.open();
        sheet.begin();
    }

    private void begin() {
        String clientId = Play.clientId(app.settings());

        if (clientId == null) {
            notAvailable();
            return;
        }

        cancelled = false;
        body.removeAll();
        body.add(new Skeleton(86, false));
        body.add(Sheet.status("Asking Microsoft for a sign-in code...", Style.MUTED));
        body.revalidate();
        body.repaint();
        MicrosoftAuth auth = new MicrosoftAuth(clientId);
        app.run(auth::start, code -> {
            if (!cancelled) {
                showCode(auth, code);
            }
        }, this::failed);
    }

    private void showCode(MicrosoftAuth auth, MicrosoftAuth.DeviceCode code) {
        body.removeAll();
        body.add(step("1", "Copy the code and open Microsoft's sign-in page."));
        body.add(codeBox(code.userCode()));
        JPanel actions = Widgets.flow(10, 8);
        Widgets.Button open = Widgets.button("Copy code and open Microsoft", Icons.Glyph.EXTERNAL,
                Widgets.Kind.PRIMARY);
        open.setFont(Style.strong(14f));
        open.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 22));
        open.addActionListener(event -> {
            copy(code.userCode());
            HomePage.browse(code.linkWithCode());
        });
        Widgets.Button copy = Widgets.button("Copy code", Icons.Glyph.COPY, Widgets.Kind.SECONDARY);
        copy.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        copy.addActionListener(event -> {
            copy(code.userCode());
            app.toast("Code copied.", Style.MUTED);
        });
        actions.add(open);
        actions.add(copy);
        body.add(actions);
        body.add(step("2", "Sign in there with your Microsoft account. This window carries on by itself."));
        JPanel waiting = Widgets.row(10);
        waiting.add(new Spinner());
        waiting.add(Sheet.status("Waiting for you to sign in at " + code.verificationUri().replaceFirst(
                "^https?://", ""), Style.FAINT));
        body.add(waiting);
        body.revalidate();
        body.repaint();

        app.run(() -> {
            Account account = auth.finish(code, () -> cancelled);
            Play.accounts().put(account);
            return account;
        }, account -> {
            if (cancelled) {
                return;
            }

            done(account);
        }, failure -> {
            if (!cancelled) {
                failed(failure);
            }
        });
    }

    private void done(Account account) {
        body.removeAll();
        JPanel row = Widgets.clear(new BorderLayout(16, 0));
        JLabel face = new JLabel();
        Heads.load(account, 64, 14, image -> face.setIcon(new javax.swing.ImageIcon(image)));
        row.add(face, BorderLayout.WEST);
        JPanel words = Widgets.stack(4);
        words.add(Widgets.line("Signed in as " + account.name(), Style.font(Font.BOLD, 17f), Style.TEXT));
        words.add(Widgets.line("Minecraft: Java Edition is on this account. Press Launch to play.",
                Style.font(Font.PLAIN, 13f), Style.MUTED));
        row.add(words, BorderLayout.CENTER);
        body.add(row);
        body.revalidate();
        body.repaint();
        app.changed();
        javax.swing.Timer later = new javax.swing.Timer(1100, event -> {
            close();
            signedIn.accept(account);
        });
        later.setRepeats(false);
        later.start();
    }

    private void failed(Throwable failure) {
        body.removeAll();
        Widgets.Card box = new Widgets.Card(Style.mix(Style.PANEL, Style.RED, 0.10f), 14, false);
        box.setLayout(new BorderLayout(12, 0));
        box.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel icon = new JLabel(Icons.of(Icons.Glyph.ALERT, 20, Style.RED));
        icon.setVerticalAlignment(SwingConstants.TOP);
        box.add(icon, BorderLayout.WEST);
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        box.add(Widgets.paragraph(message, 13.5f, Style.TEXT, 0), BorderLayout.CENTER);
        body.add(box);
        JPanel actions = Widgets.flow(10, 8);
        Widgets.Button again = Widgets.button("Try again", Icons.Glyph.REFRESH, Widgets.Kind.PRIMARY);
        again.addActionListener(event -> begin());
        actions.add(again);
        actions.add(useOfficial());
        body.add(actions);
        body.revalidate();
        body.repaint();
    }

    private void notAvailable() {
        body.removeAll();
        body.add(Widgets.paragraph("This build of Octo Launcher has no Microsoft app registration, so it cannot "
                + "sign you in itself. Add one in Settings (an Azure app ID that Mojang has approved for Minecraft), "
                + "or play through the official Minecraft Launcher: Octo selects itself there and you sign in "
                + "as usual.", 13.5f, Style.MUTED, 0));
        JPanel actions = Widgets.flow(10, 8);
        actions.add(useOfficial());
        Widgets.Button settings = Widgets.button("Open Settings", Icons.Glyph.SETTINGS, Widgets.Kind.SECONDARY);
        settings.addActionListener(event -> {
            close();
            app.go("Settings");
        });
        actions.add(settings);
        body.add(actions);
        body.revalidate();
        body.repaint();
    }

    private Widgets.Button useOfficial() {
        Widgets.Button official = Widgets.button("Use the Minecraft Launcher", Icons.Glyph.PLAY,
                Widgets.Kind.SECONDARY);
        official.addActionListener(event -> {
            app.settings().useOfficialLauncher = true;
            app.save();
            app.changed();
            close();
            app.toast("Launch now opens the Minecraft Launcher with Octo selected.", Style.MUTED);
        });
        return official;
    }

    private static void copy(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (RuntimeException e) {
            // No clipboard: the code is on screen.
        }
    }

    private static JComponent step(String number, String text) {
        JPanel row = Widgets.clear(new BorderLayout(12, 0));
        JLabel badge = new JLabel(number, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);
                g.setColor(Style.alpha(Style.VIOLET, 60));
                g.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        badge.setFont(Style.font(Font.BOLD, 12f));
        badge.setForeground(Style.TEXT);
        badge.setPreferredSize(new Dimension(24, 24));
        JPanel holder = Widgets.clear(new BorderLayout());
        holder.add(badge, BorderLayout.NORTH);
        row.add(holder, BorderLayout.WEST);
        row.add(Widgets.paragraph(text, 14f, Style.TEXT, 0), BorderLayout.CENTER);
        return row;
    }

    /** The code, large and spaced, in a box of its own. */
    private static JComponent codeBox(String code) {
        Widgets.Card box = new Widgets.Card(Style.PANEL, 16, true);
        box.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        JLabel label = new JLabel(String.join(" ", code.split("")), SwingConstants.CENTER);
        label.setFont(new Font(Font.MONOSPACED, Font.BOLD, 30));
        label.setForeground(Style.TEXT);
        box.add(label, BorderLayout.CENTER);
        return box;
    }

    /** A small turning arc: waiting. */
    static final class Spinner extends JComponent {
        private int angle;
        private final javax.swing.Timer turn = new javax.swing.Timer(30, event -> {
            angle = (angle + 12) % 360;
            repaint();
        });

        Spinner() {
            setPreferredSize(new Dimension(18, 18));
        }

        @Override
        public void addNotify() {
            super.addNotify();
            turn.start();
        }

        @Override
        public void removeNotify() {
            turn.stop();
            super.removeNotify();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            g.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            g.setColor(Style.alpha(Color.WHITE, 30));
            g.drawOval(2, 2, 14, 14);
            g.setColor(Style.VIOLET);
            g.drawArc(2, 2, 14, 14, -angle, 100);
            g.dispose();
        }
    }
}
