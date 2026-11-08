package net.neoforged.neoforge.client.event;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * The event NeoForge fired, until 26.x, to let mods recolour a tooltip, for
 * mods built then.
 *
 * <p>26.x draws tooltips from sprites and dropped the event. Mods that drew
 * their own tooltips fired it with their colours and drew with what it gave
 * back — Create's goggles overlay and every catnip tooltip — and the
 * stand-in Octo would otherwise make answers every colour as nothing, so
 * they drew fully transparent. This one keeps the colours it is given, and
 * what listeners set; none listen on 26.x, so a tooltip keeps its own.
 */
public class RenderTooltipEvent$Color extends net.neoforged.bus.api.Event {
    private final ItemStack itemStack;
    private final GuiGraphics graphics;
    private final int x;
    private final int y;
    private final Font font;
    private final List<ClientTooltipComponent> components;
    private final int originalBackground;
    private final int originalBorderStart;
    private final int originalBorderEnd;
    private int backgroundStart;
    private int backgroundEnd;
    private int borderStart;
    private int borderEnd;

    public RenderTooltipEvent$Color(ItemStack stack, GuiGraphics graphics, int x, int y, Font font, int background,
            int borderStart, int borderEnd, List<ClientTooltipComponent> components) {
        this.itemStack = stack;
        this.graphics = graphics;
        this.x = x;
        this.y = y;
        this.font = font;
        this.components = components;
        this.originalBackground = background;
        this.originalBorderStart = borderStart;
        this.originalBorderEnd = borderEnd;
        this.backgroundStart = background;
        this.backgroundEnd = background;
        this.borderStart = borderStart;
        this.borderEnd = borderEnd;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public GuiGraphics getGraphics() {
        return graphics;
    }

    public List<ClientTooltipComponent> getComponents() {
        return components;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public Font getFont() {
        return font;
    }

    public int getOriginalBackgroundStart() {
        return originalBackground;
    }

    public int getOriginalBackgroundEnd() {
        return originalBackground;
    }

    public int getOriginalBorderStart() {
        return originalBorderStart;
    }

    public int getOriginalBorderEnd() {
        return originalBorderEnd;
    }

    public int getBackgroundStart() {
        return backgroundStart;
    }

    public int getBackgroundEnd() {
        return backgroundEnd;
    }

    public int getBorderStart() {
        return borderStart;
    }

    public int getBorderEnd() {
        return borderEnd;
    }

    public void setBackground(int background) {
        this.backgroundStart = background;
        this.backgroundEnd = background;
    }

    public void setBackgroundStart(int backgroundStart) {
        this.backgroundStart = backgroundStart;
    }

    public void setBackgroundEnd(int backgroundEnd) {
        this.backgroundEnd = backgroundEnd;
    }

    public void setBorderStart(int borderStart) {
        this.borderStart = borderStart;
    }

    public void setBorderEnd(int borderEnd) {
        this.borderEnd = borderEnd;
    }
}
