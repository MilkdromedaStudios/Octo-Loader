package studios.milkdromeda.octo.launcher.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

/**
 * A creeper, standing in the banner: built as boxes and drawn in three
 * quarters with its own texture.
 *
 * <p>The texture is the player's: read from the Minecraft they have
 * installed, never shipped with Octo. With no Minecraft yet there is no
 * creeper, and the banner shows the Octo mark instead.
 */
final class MobArt {
    private static final String TEXTURE = "assets/minecraft/textures/entity/creeper/creeper.png";

    /** One box of the model, in texture pixels, with where its faces are on the texture. */
    private record Box(double x0, double y0, double z0, double w, double h, double d, int u, int v) {
    }

    // The creeper, as the game builds it: four legs, a body, a head.
    private static final List<Box> MODEL = List.of(
            new Box(-4, 0, -6, 4, 6, 4, 0, 16), // back left leg
            new Box(0, 0, -6, 4, 6, 4, 0, 16), // back right leg
            new Box(-4, 6, -2, 8, 12, 4, 16, 16), // body
            new Box(-4, 0, 2, 4, 6, 4, 0, 16), // front left leg
            new Box(0, 0, 2, 4, 6, 4, 0, 16), // front right leg
            new Box(-4, 18, -4, 8, 8, 8, 0, 0)); // head

    // The view: x runs right and a little down, z (towards the viewer) left and down, y up.
    private static final double[] EX = { 0.80, 0.36 };
    private static final double[] EZ = { -0.74, 0.30 };

    private MobArt() {
    }

    /** The creeper's texture from an installed Minecraft, newest first, or {@code null}. */
    static BufferedImage texture(Path gameDirectory, String preferred) {
        List<Path> jars = new ArrayList<>();
        Path versions = gameDirectory.resolve("versions");

        if (preferred != null && !preferred.isBlank()) {
            jars.add(versions.resolve(preferred).resolve(preferred + ".jar"));
        }

        try (var folders = Files.list(versions)) {
            folders.sorted(Comparator.comparing(Path::getFileName).reversed())
                    .map(folder -> folder.resolve(folder.getFileName() + ".jar")).forEach(jars::add);
        } catch (Exception e) {
            // No versions folder.
        }

        for (Path jar : jars) {
            try {
                if (!Files.isRegularFile(jar) || Files.size(jar) < 1_000_000) {
                    continue; // Octo's own profiles carry an empty jar.
                }

                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    ZipEntry entry = zip.getEntry(TEXTURE);

                    if (entry != null) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            BufferedImage image = ImageIO.read(in);

                            if (image != null && image.getWidth() >= 64) {
                                return image;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // The next one.
            }
        }

        return null;
    }

    /** The creeper drawn to a height in pixels, on a transparent image. */
    static BufferedImage render(BufferedImage texture, int height) {
        int unit = texture.getWidth() / 64;
        // The model's extent on screen, to scale and place it.
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (Box box : MODEL) {
            for (int corner = 0; corner < 8; corner++) {
                Point2D p = project(box.x0 + ((corner & 1) == 0 ? 0 : box.w), box.y0 + ((corner & 2) == 0 ? 0 : box.h),
                        box.z0 + ((corner & 4) == 0 ? 0 : box.d), 1, 0, 0);
                minX = Math.min(minX, p.getX());
                maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY());
                maxY = Math.max(maxY, p.getY());
            }
        }

        double scale = (height - 24) / (maxY - minY);
        int width = (int) Math.ceil((maxX - minX) * scale) + 24;
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double originX = -minX * scale + 12;
        double originY = -minY * scale + 4;

        // A soft shadow on the ground under its feet.
        Point2D feet = project(0, 0, 0, scale, originX, originY);
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new java.awt.geom.Ellipse2D.Double(feet.getX() - scale * 7, feet.getY() - scale * 2.2, scale * 14,
                scale * 4.4));

        for (Box box : MODEL) {
            double x1 = box.x0 + box.w;
            double y1 = box.y0 + box.h;
            double z1 = box.z0 + box.d;
            int u = box.u * unit;
            int v = box.v * unit;
            int w = (int) box.w * unit;
            int h = (int) box.h * unit;
            int d = (int) box.d * unit;

            // Top: across x, from the back (z0) to the front (z1).
            face(g, texture.getSubimage(u + d, v, w, d), project(box.x0, y1, box.z0, scale, originX, originY),
                    project(x1, y1, box.z0, scale, originX, originY), project(box.x0, y1, z1, scale, originX, originY),
                    0.0f);
            // Front, facing the viewer: the face.
            face(g, texture.getSubimage(u + d, v + d, w, h), project(box.x0, y1, z1, scale, originX, originY),
                    project(x1, y1, z1, scale, originX, originY), project(box.x0, box.y0, z1, scale, originX, originY),
                    0.16f);
            // The side to the right, from the front to the back.
            face(g, texture.getSubimage(u + d + w, v + d, d, h), project(x1, y1, z1, scale, originX, originY),
                    project(x1, y1, box.z0, scale, originX, originY), project(x1, box.y0, z1, scale, originX, originY),
                    0.36f);
        }

        g.dispose();
        return out;
    }

    private static Point2D project(double x, double y, double z, double scale, double originX, double originY) {
        return new Point2D.Double(originX + (x * EX[0] + z * EZ[0]) * scale,
                originY + (x * EX[1] + z * EZ[1] - y) * scale);
    }

    /** A texture region laid on the parallelogram at {@code origin}, spanned towards {@code across} and {@code down}. */
    private static void face(Graphics2D g, BufferedImage region, Point2D origin, Point2D across, Point2D down,
            float shade) {
        AffineTransform transform = new AffineTransform(
                (across.getX() - origin.getX()) / region.getWidth(), (across.getY() - origin.getY()) / region.getWidth(),
                (down.getX() - origin.getX()) / region.getHeight(), (down.getY() - origin.getY()) / region.getHeight(),
                origin.getX(), origin.getY());
        g.drawImage(region, transform, null);

        if (shade > 0) {
            Path2D shape = new Path2D.Double();
            shape.moveTo(origin.getX(), origin.getY());
            shape.lineTo(across.getX(), across.getY());
            shape.lineTo(across.getX() + down.getX() - origin.getX(), across.getY() + down.getY() - origin.getY());
            shape.lineTo(down.getX(), down.getY());
            shape.closePath();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, shade));
            g.setColor(Color.BLACK);
            g.fill(shape);
            g.setComposite(AlphaComposite.SrcOver);
        }
    }
}
