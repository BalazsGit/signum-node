package application.utils.gui;

import jiconfont.icons.font_awesome.FontAwesome;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Dimension;
import java.util.function.Supplier;
import javax.swing.JButton;

/**
 * Semantic icon definitions for profile-management actions.
 *
 * <p>Icons are created on demand so their size and palette reflect the current
 * GUI settings. The catalog also builds fixed-layout normal/rollover pairs,
 * with optional explicit size, color, and hover-scale parameters. Button
 * behavior and tooltips remain the responsibility of the calling panel.</p>
 */
public enum ProfileActionIcon {
    NEW_PROFILE(plusGlyph(), GuiColors::getButtonIcon),
    SAVE_PROFILE(fontAwesome(FontAwesome.FLOPPY_O), GuiColors::getSaved),
    APPLY_PROFILE(fontAwesome(FontAwesome.CHECK_CIRCLE_O), GuiColors::getButtonIcon),
    RENAME_PROFILE(fontAwesome(FontAwesome.PENCIL_SQUARE_O), GuiColors::getButtonIcon),
    DELETE_PROFILE(fontAwesome(FontAwesome.TRASH_O), GuiColors::getContrastRed),
    RELOAD_PROFILE(fontAwesome(FontAwesome.RECYCLE), GuiColors::getButtonIcon),
    REFRESH_PROFILES(fontAwesome(FontAwesome.REFRESH), GuiColors::getButtonIcon),
    RESET_PROFILE_TO_DEFAULTS(fontAwesome(FontAwesome.UNDO), GuiColors::getButtonIcon),
    COPY_PROFILE(fontAwesome(FontAwesome.CLIPBOARD), GuiColors::getButtonIcon),
    CLONE_PROFILE(fontAwesome(FontAwesome.FILES_O), GuiColors::getButtonIcon);

    private final IconFactory iconFactory;
    private final Supplier<Color> colorSupplier;

    ProfileActionIcon(IconFactory iconFactory, Supplier<Color> colorSupplier) {
        this.iconFactory = iconFactory;
        this.colorSupplier = colorSupplier;
    }

    public Icon normal(float size) {
        return iconFactory.create(size, colorSupplier);
    }

    public Icon rollover(float size) {
        return rollover(size, HoverScaleIcon.DEFAULT_SCALE);
    }

    public Icon normal(float size, Color color) {
        return iconFactory.create(size, () -> color);
    }

    public Icon rollover(float size, float scale) {
        if (!Float.isFinite(scale) || scale <= 0f) {
            throw new IllegalArgumentException("Hover scale must be a positive finite number: " + scale);
        }
        return normal(size * scale);
    }

    public Icon rollover(float size, float scale, Color color) {
        if (!Float.isFinite(scale) || scale <= 0f) {
            throw new IllegalArgumentException("Hover scale must be a positive finite number: " + scale);
        }
        return normal(size * scale, color);
    }

    public void install(JButton button, float size) {
        install(button, size, HoverScaleIcon.DEFAULT_SCALE);
    }

    public void install(JButton button, float size, float hoverScale) {
        if (button == null) {
            return;
        }
        if (!Float.isFinite(hoverScale) || hoverScale <= 0f) {
            throw new IllegalArgumentException("Hover scale must be a positive finite number: " + hoverScale);
        }
        HoverScaleIcon.install(button, normal(size), rollover(size, hoverScale));
    }

    public void install(JButton button, float size, float hoverScale, Color color) {
        if (button == null) {
            return;
        }
        if (!Float.isFinite(hoverScale) || hoverScale <= 0f) {
            throw new IllegalArgumentException("Hover scale must be a positive finite number: " + hoverScale);
        }
        HoverScaleIcon.install(button, normal(size, color), rollover(size, hoverScale, color));
    }

    private static IconFactory fontAwesome(FontAwesome glyph) {
        return (size, colorSupplier) -> IconFontCache.buildIcon(glyph, size, colorSupplier.get());
    }

    /**
     * The shared thin "+" glyph ({@link PlusGlyphIcon}, the same one the browser's
     * plus buttons use), pre-rendered offscreen. It is sized to the SAME non-square
     * font-metric box the FontAwesome siblings expose ({@link
     * IconFontCache#iconBox}), so the "+" reads as the same visual size as the other
     * toolbar icons, grows on hover in step with them, and keeps the row's margins
     * uniform.
     */
    private static IconFactory plusGlyph() {
        return (size, colorSupplier) -> {
            Dimension box = IconFontCache.iconBox(FontAwesome.BARS, size);
            return new PlusGlyphIcon(box.width, box.height, colorSupplier.get());
        };
    }

    @FunctionalInterface
    private interface IconFactory {
        Icon create(float size, Supplier<Color> colorSupplier);
    }
}
