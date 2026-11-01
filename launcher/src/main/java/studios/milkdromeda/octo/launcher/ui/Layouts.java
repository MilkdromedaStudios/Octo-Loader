package studios.milkdromeda.octo.launcher.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Layouts that know text wraps: each measures a child at the width it will
 * really have before asking how tall it wants to be. Swing's own ask a
 * paragraph's height before its width is known, which is how text came to be
 * cut off in a narrow window and stranded in a wide one.
 */
public final class Layouts {
    private Layouts() {
    }

    /** How tall a component wants to be at a width: sized, laid out all the way down, then asked. */
    static int heightAt(Component component, int width) {
        component.setSize(Math.max(1, width), Math.max(1, component.getHeight()));
        layoutTree(component);
        // Text sizes itself from its width, which the layout above just set.
        return component.getPreferredSize().height;
    }

    private static void layoutTree(Component component) {
        if (component instanceof Container container) {
            container.doLayout();

            for (Component child : container.getComponents()) {
                if (child.isVisible()) {
                    layoutTree(child);
                }
            }
        }
    }

    private static int width(Container parent, int fallback) {
        Insets insets = parent.getInsets();
        int width = parent.getWidth() > 0 ? parent.getWidth() : fallback;
        return Math.max(1, width - insets.left - insets.right);
    }

    // ----------------------------------------------------------------- stack

    /** Children one above another, each as wide as the parent and as tall as it needs at that width. */
    public static final class Stack implements LayoutManager {
        private final int gap;

        public Stack(int gap) {
            this.gap = gap;
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int width = width(parent, widest(parent));
            int height = 0;
            int shown = 0;

            for (Component child : parent.getComponents()) {
                if (child.isVisible()) {
                    height += heightAt(child, width);
                    shown++;
                }
            }

            height += Math.max(0, shown - 1) * gap;
            return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        private static int widest(Container parent) {
            int widest = 0;

            for (Component child : parent.getComponents()) {
                if (child.isVisible()) {
                    widest = Math.max(widest, child.getPreferredSize().width);
                }
            }

            return widest;
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(0, 0);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int width = width(parent, 1);
            int y = insets.top;

            for (Component child : parent.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }

                int height = heightAt(child, width);
                child.setBounds(insets.left, y, width, height);
                y += height + gap;
            }
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }
    }

    // ------------------------------------------------------------------ grid

    /**
     * Cards in as many columns as fit at a minimum width, up to a maximum;
     * each row as tall as its tallest card, every card in it that tall.
     */
    public static final class Grid implements LayoutManager {
        private final int minColumn;
        private final int maxColumns;
        private final int gap;

        public Grid(int minColumn, int maxColumns, int gap) {
            this.minColumn = minColumn;
            this.maxColumns = maxColumns;
            this.gap = gap;
        }

        int columns(int width) {
            return Math.max(1, Math.min(maxColumns, (width + gap) / (minColumn + gap)));
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int width = width(parent, minColumn * 2 + gap);
            return new Dimension(width + insets.left + insets.right,
                    place(parent, width, false) + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(minColumn, 0);
        }

        @Override
        public void layoutContainer(Container parent) {
            place(parent, width(parent, 1), true);
        }

        /** @return the total height */
        private int place(Container parent, int width, boolean apply) {
            Insets insets = parent.getInsets();
            int columns = columns(width);
            int cell = (width - gap * (columns - 1)) / columns;
            Component[] children = java.util.Arrays.stream(parent.getComponents()).filter(Component::isVisible)
                    .toArray(Component[]::new);
            int y = insets.top;

            for (int start = 0; start < children.length; start += columns) {
                int rowHeight = 0;
                int end = Math.min(children.length, start + columns);

                for (int i = start; i < end; i++) {
                    rowHeight = Math.max(rowHeight, heightAt(children[i], cell));
                }

                if (apply) {
                    for (int i = start; i < end; i++) {
                        children[i].setBounds(insets.left + (i - start) * (cell + gap), y, cell, rowHeight);
                    }
                }

                y += rowHeight + (end < children.length ? gap : 0);
            }

            return y - insets.top;
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }
    }

    // ------------------------------------------------------------------ flow

    /** Left to right, on to the next line when the line is full: chips and badges. */
    public static final class Flow implements LayoutManager {
        private final int hgap;
        private final int vgap;

        public Flow(int hgap, int vgap) {
            this.hgap = hgap;
            this.vgap = vgap;
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int available = parent.getWidth() > 0 ? parent.getWidth() - insets.left - insets.right
                    : Integer.MAX_VALUE;
            int[] size = arrange(parent, available, false);
            return new Dimension(size[0] + insets.left + insets.right, size[1] + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(0, 0);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            arrange(parent, Math.max(1, parent.getWidth() - insets.left - insets.right), true);
        }

        /** @return the widest line and the total height */
        private int[] arrange(Container parent, int available, boolean apply) {
            Insets insets = parent.getInsets();
            int x = 0;
            int y = 0;
            int lineHeight = 0;
            int widest = 0;

            for (Component child : parent.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }

                Dimension size = child.getPreferredSize();

                if (x > 0 && x + size.width > available) {
                    x = 0;
                    y += lineHeight + vgap;
                    lineHeight = 0;
                }

                if (apply) {
                    child.setBounds(insets.left + x, insets.top + y, size.width, size.height);
                }

                x += size.width + hgap;
                widest = Math.max(widest, x - hgap);
                lineHeight = Math.max(lineHeight, size.height);
            }

            return new int[] { widest, y + lineHeight };
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }
    }
}
