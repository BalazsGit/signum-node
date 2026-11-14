package application.module.database.gui;

import application.utils.gui.GuiColors;
import application.utils.gui.IconFontCache;
import jiconfont.icons.font_awesome.FontAwesome;

import javax.swing.Icon;
import java.awt.Color;
import java.util.function.Supplier;

/**
 * Semantic icon definitions shared by database configuration and profile panels.
 */
enum DatabaseActionIcon {
    VALIDATION_SUCCESS(FontAwesome.CHECK_CIRCLE, GuiColors::getApplied),
    VALIDATION_ERROR(FontAwesome.TIMES_CIRCLE, GuiColors::getContrastRed),
    START(FontAwesome.PLAY, GuiColors::getApplied),
    STOP(FontAwesome.STOP, GuiColors::getContrastRed),
    RESTART(FontAwesome.REFRESH, GuiColors::getSaved);

    private final FontAwesome glyph;
    private final Supplier<Color> colorSupplier;

    DatabaseActionIcon(FontAwesome glyph, Supplier<Color> colorSupplier) {
        this.glyph = glyph;
        this.colorSupplier = colorSupplier;
    }

    Icon icon(float size) {
        return IconFontCache.buildIcon(glyph, size, colorSupplier.get());
    }
}
