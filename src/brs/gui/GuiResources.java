package brs.gui;

/**
 * A central place for all GUI resource path constants.
 */
public final class GuiResources {

    private GuiResources() {
        // prevent instantiation
    }

    // General resources
    public static final String IMAGES_PATH = "images/";

    // Signum specific images
    public static final String SIGNUM_NODE_WHITE_SVG = IMAGES_PATH + "signum_node_white.svg";
    public static final String SIGNUM_NODE_BLACK_SVG = IMAGES_PATH + "signum_node_black.svg";

    // FlatLaf specific resources
    public static final String FLATLAF_RESOURCE_PATH = "flatlaf/";
    public static final String FLATLAF_ICONS_PATH = FLATLAF_RESOURCE_PATH + "icons/";
    public static final String FLATLAF_THEMES_PATH = FLATLAF_RESOURCE_PATH + "intellijthemes/";

    // From IJThemesPanel, seems to be a package path for loading classes, not files
    public static final String IJ_THEMES_PACKAGE_PATH = "/com/formdev/flatlaf/intellijthemes/themes/";
}