package application.module.database.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import application.utils.gui.GuiColors;
import application.utils.gui.IconFontCache;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Database action icon catalog")
class DatabaseActionIconTest {

    @BeforeAll
    static void registerIconFont() {
        IconFontSwing.register(FontAwesome.getIconFont());
    }

    @Test
    @DisplayName("all database action icons remain close to the requested dimensions")
    void iconsHonorRequestedDimensions() {
        for (DatabaseActionIcon action : DatabaseActionIcon.values()) {
            Icon icon = action.icon(15);

            assertNotNull(icon, action.name());
            assertTrue(Math.abs(15 - icon.getIconWidth()) <= 2, action.name() + " width");
            assertTrue(Math.abs(15 - icon.getIconHeight()) <= 2, action.name() + " height");
        }
    }

    @Test
    @DisplayName("database action icons remain usable at the application's minimum common size")
    void iconsAreUsableAtSmallSize() {
        for (DatabaseActionIcon action : DatabaseActionIcon.values()) {
            Icon icon = action.icon(8);
            assertTrue(icon.getIconWidth() > 0, action.name() + " width");
            assertTrue(icon.getIconHeight() > 0, action.name() + " height");
        }
    }

    @Test
    @DisplayName("icons use the current palette color for their semantic action")
    void iconsResolveCurrentPaletteColors() {
        assertSame(IconFontCache.buildIcon(FontAwesome.CHECK_CIRCLE, 15, GuiColors.getApplied()),
                DatabaseActionIcon.VALIDATION_SUCCESS.icon(15));
        assertSame(IconFontCache.buildIcon(FontAwesome.TIMES_CIRCLE, 15, GuiColors.getContrastRed()),
                DatabaseActionIcon.VALIDATION_ERROR.icon(15));
        assertSame(IconFontCache.buildIcon(FontAwesome.PLAY, 15, GuiColors.getApplied()),
                DatabaseActionIcon.START.icon(15));
        assertSame(IconFontCache.buildIcon(FontAwesome.STOP, 15, GuiColors.getContrastRed()),
                DatabaseActionIcon.STOP.icon(15));
        assertSame(IconFontCache.buildIcon(FontAwesome.REFRESH, 15, GuiColors.getSaved()),
                DatabaseActionIcon.RESTART.icon(15));
    }
}
