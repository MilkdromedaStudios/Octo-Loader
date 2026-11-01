package studios.milkdromeda.octo.launcher.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A player drawn in three quarters from their skin, turned to any angle: the
 * model's boxes, the skin's outer layer just outside them, and a cape behind.
 *
 * <p>Each face is a region of the skin laid on its parallelogram, shaded by
 * which way it faces, and drawn far to near.
 */
final class PlayerArt {
    /** A box of the model, in texture pixels: where it is, its size, and its corner on the texture. */
    private record Box(double x0, double y0, double z0, double w, double h, double d, int u, int v, double grow,
            boolean cape) {
    }

    /** A face ready to draw: the texture region and its three projected corners, and how far away it is. */
    private record Face(BufferedImage region, Point2D origin, Point2D across, Point2D down, double depth) {
    }

    // The direction the viewer looks from: right, above, in front.
    private static final double[] VIEW = { 0.45, 0.55, 0.70 };
    private static final double[] EX = { 0.82, 0.30 };
    private static final double[] EZ = { -0.62, 0.28 };

    private PlayerArt() {
    }

    /** A 64×32 skin from before 1.8, made into the 64×64 layout: the left limbs mirror the right ones. */
    static BufferedImage modern(BufferedImage skin) {
        int unit = skin.getWidth() / 64;

        if (skin.getHeight() == skin.getWidth()) {
            return skin;
        }

        BufferedImage out = new BufferedImage(64 * unit, 64 * unit, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(skin, 0, 0, null);
        mirrorLimb(g, skin, unit, 0, 16, 16, 48);
        mirrorLimb(g, skin, unit, 40, 16, 32, 48);
        g.dispose();
        return out;
    }

    /** Copies a 4×12×4 limb's faces to another place, each face flipped left to right. */
    private static void mirrorLimb(Graphics2D g, BufferedImage skin, int unit, int fromU, int fromV, int toU,
            int toV) {
        int[][] faces = {
                // top, bottom: 4×4 at (4,0) and (8,0)
                { 4, 0, 4, 0, 4, 4 }, { 8, 0, 8, 0, 4, 4 },
                // right, front, left, back: 4×12, the sides swapping places
                { 0, 4, 8, 4, 4, 12 }, { 4, 4, 4, 4, 4, 12 }, { 8, 4, 0, 4, 4, 12 }, { 12, 4, 12, 4, 4, 12 } };

        for (int[] face : faces) {
            int sx = (fromU + face[0]) * unit;
            int sy = (fromV + face[1]) * unit;
            int dx = (toU + face[2]) * unit;
            int dy = (toV + face[3]) * unit;
            int w = face[4] * unit;
            int h = face[5] * unit;
            g.drawImage(skin, dx + w, dy, dx, dy + h, sx, sy, sx + w, sy + h, null);
        }
    }

    private static List<Box> model(boolean slim, boolean withCape) {
        int arm = slim ? 3 : 4;
        List<Box> boxes = new ArrayList<>();

        if (withCape) {
            boxes.add(new Box(-5, 8, -3.2, 10, 16, 1, 0, 0, 0, true));
        }

        // Legs, body, arms and head; then the outer layer of each, a little larger.
        boxes.add(new Box(-4, 0, -2, 4, 12, 4, 0, 16, 0, false));
        boxes.add(new Box(0, 0, -2, 4, 12, 4, 16, 48, 0, false));
        boxes.add(new Box(-4, 12, -2, 8, 12, 4, 16, 16, 0, false));
        boxes.add(new Box(-4 - arm, 12, -2, arm, 12, 4, 40, 16, 0, false));
        boxes.add(new Box(4, 12, -2, arm, 12, 4, 32, 48, 0, false));
        boxes.add(new Box(-4, 24, -4, 8, 8, 8, 0, 0, 0, false));
        boxes.add(new Box(-4, 0, -2, 4, 12, 4, 0, 32, 0.25, false));
        boxes.add(new Box(0, 0, -2, 4, 12, 4, 0, 48, 0.25, false));
        boxes.add(new Box(-4, 12, -2, 8, 12, 4, 16, 32, 0.25, false));
        boxes.add(new Box(-4 - arm, 12, -2, arm, 12, 4, 40, 32, 0.25, false));
        boxes.add(new Box(4, 12, -2, arm, 12, 4, 48, 48, 0.25, false));
        boxes.add(new Box(-4, 24, -4, 8, 8, 8, 32, 0, 0.5, false));
        return boxes;
    }

    /**
     * The player at a height in pixels.
     *
     * @param yaw degrees: 0 faces the viewer, 180 shows the back
     * @param cape a cape texture (64×32 layout), or {@code null}
     */
    static BufferedImage render(BufferedImage skinTexture, boolean slim, BufferedImage cape, double yaw, int height) {
        BufferedImage skin = modern(skinTexture);
        int unit = skin.getWidth() / 64;
        int capeUnit = cape == null ? 1 : Math.max(1, cape.getWidth() / 64);
        double angle = Math.toRadians(yaw);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        List<Box> boxes = model(slim, cape != null);
        List<double[][]> quads = new ArrayList<>();
        List<BufferedImage> regions = new ArrayList<>();
        List<double[]> normals = new ArrayList<>();
        // Sorting by face centres alone puts a long cape in front of the legs: its inside
        // is drawn before everything, its outside just after the body's back.
        List<Double> bias = new ArrayList<>();

        for (Box box : boxes) {
            double g = box.grow();
            double x0 = box.x0() - g;
            double y0 = box.y0() - g;
            double z0 = box.z0() - g;
            double x1 = box.x0() + box.w() + g;
            double y1 = box.y0() + box.h() + g;
            double z1 = box.z0() + box.d() + g;
            BufferedImage texture = box.cape() ? cape : skin;
            int k = box.cape() ? capeUnit : unit;
            int u = box.u() * k;
            int v = box.v() * k;
            int w = (int) box.w() * k;
            int h = (int) box.h() * k;
            int d = (int) box.d() * k;

            if (box.cape()) {
                // A cape's texture: its outside faces back, the inside faces the player.
                add(quads, regions, normals, texture, u + d, v + d, w, h, new double[][] { { x1, y1, z0 },
                        { x0, y1, z0 }, { x1, y0, z0 } }, new double[] { 0, 0, -1 }, 0.10);
                pad(bias, quads.size(), 3);
                add(quads, regions, normals, texture, u + d + w + d, v + d, w, h, new double[][] { { x0, y1, z1 },
                        { x1, y1, z1 }, { x0, y0, z1 } }, new double[] { 0, 0, 1 }, 0.10);
                pad(bias, quads.size(), -1e6);
                continue;
            }

            // Top, front, back, the player's right side (-x) and left side (+x).
            add(quads, regions, normals, texture, u + d, v, w, d, new double[][] { { x0, y1, z0 }, { x1, y1, z0 },
                    { x0, y1, z1 } }, new double[] { 0, 1, 0 }, 0.0);
            add(quads, regions, normals, texture, u + d, v + d, w, h, new double[][] { { x0, y1, z1 },
                    { x1, y1, z1 }, { x0, y0, z1 } }, new double[] { 0, 0, 1 }, 0.12);
            add(quads, regions, normals, texture, u + d + w + d, v + d, w, h, new double[][] { { x1, y1, z0 },
                    { x0, y1, z0 }, { x1, y0, z0 } }, new double[] { 0, 0, -1 }, 0.12);
            add(quads, regions, normals, texture, u, v + d, d, h, new double[][] { { x0, y1, z0 }, { x0, y1, z1 },
                    { x0, y0, z0 } }, new double[] { -1, 0, 0 }, 0.30);
            add(quads, regions, normals, texture, u + d + w, v + d, d, h, new double[][] { { x1, y1, z1 },
                    { x1, y1, z0 }, { x1, y0, z1 } }, new double[] { 1, 0, 0 }, 0.30);
            pad(bias, quads.size(), 0);
        }

        // Turned, projected, culled and sorted far to near.
        List<Face> faces = new ArrayList<>();
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (int i = 0; i < quads.size(); i++) {
            double[] n = turn(normals.get(i), cos, sin);

            if (n[0] * VIEW[0] + n[1] * VIEW[1] + n[2] * VIEW[2] <= 0.001) {
                continue;
            }

            double[][] corners = quads.get(i);
            Point2D[] points = new Point2D[3];
            double depth = 0;

            for (int c = 0; c < 3; c++) {
                double[] p = turn(corners[c], cos, sin);
                points[c] = new Point2D.Double(p[0] * EX[0] + p[2] * EZ[0], p[0] * EX[1] + p[2] * EZ[1] - p[1]);
                depth += p[0] * VIEW[0] + p[1] * VIEW[1] + p[2] * VIEW[2];
            }

            // The fourth corner, for the bounds.
            Point2D far = new Point2D.Double(points[1].getX() + points[2].getX() - points[0].getX(),
                    points[1].getY() + points[2].getY() - points[0].getY());

            for (Point2D p : new Point2D[] { points[0], points[1], points[2], far }) {
                minX = Math.min(minX, p.getX());
                maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY());
                maxY = Math.max(maxY, p.getY());
            }

            faces.add(new Face(regions.get(i), points[0], points[1], points[2], depth + bias.get(i)));
        }

        faces.sort(Comparator.comparingDouble(Face::depth));
        double scale = (height - 16) / (maxY - minY);
        int width = (int) Math.ceil((maxX - minX) * scale) + 16;
        BufferedImage out = new BufferedImage(Math.max(1, width), height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        double ox = -minX * scale + 8;
        double oy = -minY * scale + 8;

        for (Face face : faces) {
            Point2D o = new Point2D.Double(ox + face.origin().getX() * scale, oy + face.origin().getY() * scale);
            Point2D a = new Point2D.Double(ox + face.across().getX() * scale, oy + face.across().getY() * scale);
            Point2D d = new Point2D.Double(ox + face.down().getX() * scale, oy + face.down().getY() * scale);
            BufferedImage region = face.region();
            AffineTransform transform = new AffineTransform(
                    (a.getX() - o.getX()) / region.getWidth(), (a.getY() - o.getY()) / region.getWidth(),
                    (d.getX() - o.getX()) / region.getHeight(), (d.getY() - o.getY()) / region.getHeight(),
                    o.getX(), o.getY());
            g.drawImage(region, transform, null);
        }

        g.dispose();
        return out;
    }

    /** Gives the faces added since the last call their sorting bias. */
    private static void pad(List<Double> bias, int faces, double value) {
        while (bias.size() < faces) {
            bias.add(value);
        }
    }

    private static double[] turn(double[] p, double cos, double sin) {
        return new double[] { p[0] * cos + p[2] * sin, p[1], -p[0] * sin + p[2] * cos };
    }

    /** A face's texture region, shaded by darkening its colour and keeping its transparency. */
    private static void add(List<double[][]> quads, List<BufferedImage> regions, List<double[]> normals,
            BufferedImage texture, int u, int v, int w, int h, double[][] corners, double[] normal, double shade) {
        if (w <= 0 || h <= 0 || u + w > texture.getWidth() || v + h > texture.getHeight()) {
            return;
        }

        BufferedImage region = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        boolean any = false;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = texture.getRGB(u + x, v + y);
                int alpha = argb >>> 24;

                if (alpha == 0) {
                    continue;
                }

                any = true;
                double keep = 1 - shade;
                int r = (int) (((argb >> 16) & 0xFF) * keep);
                int gr = (int) (((argb >> 8) & 0xFF) * keep);
                int b = (int) ((argb & 0xFF) * keep);
                region.setRGB(x, y, (alpha << 24) | (r << 16) | (gr << 8) | b);
            }
        }

        if (any) {
            quads.add(corners);
            regions.add(region);
            normals.add(normal);
        }
    }

    /** A cape's outside, flat, for a card. */
    static BufferedImage capeFront(BufferedImage cape, int height) {
        int unit = Math.max(1, cape.getWidth() / 64);
        BufferedImage front = cape.getSubimage(unit, unit, 10 * unit, 16 * unit);
        int width = height * 10 / 16;
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(front, 0, 0, width, height, null);
        g.dispose();
        return out;
    }
}
