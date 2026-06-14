package application.utils.gui;

import java.awt.Dimension;
import java.awt.Font;
import javax.swing.UIManager;

/**
 * A central place for all GUI constants like sizes and dimensions.
 */
public final class GuiConstants {

    private GuiConstants() {
    } // prevent instantiation

    public static float getToolBarIconSize() {
        Font font = UIManager.getFont("Button.font");
        if (font == null)
            font = UIManager.getFont("Label.font");
        return font != null ? font.getSize() * 1.2f : 14f;
    }

    public static float getButtonIconSize() {
        Font font = UIManager.getFont("Button.font");
        if (font == null)
            font = UIManager.getFont("Label.font");
        return font != null ? font.getSize() * 1.2f : 14f;
    }

    public static float getHelpIconSize() {
        Font font = UIManager.getFont("Label.font");
        return font != null ? font.getSize() * 1.2f : 14f;
    }

    public static final int ICON_SIZE_DIALOG = 32;
    public static final Dimension PROGRESS_BAR_SIZE_SMALL = new Dimension(150, 20);
    public static final Dimension PROGRESS_BAR_SIZE_MEDIUM = new Dimension(250, 20);
    public static final Dimension PROGRESS_BAR_SIZE_LARGE = new Dimension(350, 20);
    public static final Dimension VERTICAL_SEPARATOR_SIZE = new Dimension(2, 20);

    public static final Dimension CHART_DIMENSION_LARGE = new Dimension(360, 270);
    public static final Dimension CHART_DIMENSION_MEDIUM = new Dimension(320, 240);
    public static final Dimension CHART_DIMENSION_NET_SPLIT = new Dimension(320, 60);
    public static final Dimension PIE_CHART_DIMENSION = new Dimension(300, 180);
    public static final int SLIDER_WIDTH = 150;
    public static final int TABLE_PANEL_WIDTH = 300;
}