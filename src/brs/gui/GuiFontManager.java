package brs.gui;

import com.formdev.flatlaf.FlatLaf;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.plot.XYPlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized manager for handling font updates across the GUI.
 */
public class GuiFontManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(GuiFontManager.class);

    private static Font lastGlobalFont;
    private static Font lastConsoleFont;

    /**
     * Updates UIManager font keys and applies them to the current theme.
     * If not using FlatLaf, it "locks" the font by using a plain Font instead of
     * FontUIResource.
     */
    public static void updateUIManager(Font font) {
        LookAndFeel laf = UIManager.getLookAndFeel();
        boolean isFlatLaf = laf instanceof FlatLaf;

        if (font != null) {
            if (lastGlobalFont != null && !lastGlobalFont.equals(font)) {
                LOGGER.info("Global font changed from {} {} to {} {}",
                        lastGlobalFont.getFamily(), lastGlobalFont.getSize(), font.getFamily(), font.getSize());
            } else if (lastGlobalFont == null) {
                LOGGER.info("Setting global font to {} {}", font.getFamily(), font.getSize());
            }
            lastGlobalFont = font;
        } else {
            LOGGER.info("Clearing custom global font settings.");
            lastGlobalFont = null;
        }

        if (font == null) {
            // Clear custom global font settings from UIManager to allow L&F defaults to be
            // used.
            UIManager.put("defaultFont", null);
            for (Object key : UIManager.getDefaults().keySet().toArray()) {
                if (key instanceof String && isFontKey((String) key)) {
                    UIManager.put(key, null);
                }
            }
            return;
        }

        Font baseFont = font;

        // Always use FontUIResource to allow standard Swing UI updates without
        // "locking" the font
        FontUIResource fontValue = new FontUIResource(baseFont);

        UIManager.put("defaultFont", fontValue);

        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        // Use toArray to avoid ConcurrentModificationException if the UI is updating in
        // another thread
        Object[] keys = defaults.keySet().toArray();
        Object[] uiKeys = UIManager.getDefaults().keySet().toArray();

        int count = 0;
        for (Object key : keys) {
            if (key instanceof String) {
                String k = (String) key;
                String low = k.toLowerCase();
                if (low.endsWith(".font") || low.contains("font") || low.equals("defaultfont")) {
                    UIManager.put(k, fontValue);
                    count++;
                }
            }
        }
        for (Object key : uiKeys) {
            if (key instanceof String) {
                String k = (String) key;
                String low = k.toLowerCase();
                if (low.endsWith(".font") || low.contains("font") || low.equals("defaultfont")) {
                    UIManager.put(k, fontValue);
                    count++;
                }
            }
        }
    }

    private static boolean isFontKey(String key) {
        String low = key.toLowerCase();
        return low.endsWith(".font") || low.contains("font") || low.equals("defaultfont");
    }

    /**
     * Updates only the console-specific font settings in the UIManager.
     * This allows the console to have a different font from the rest of the UI.
     */
    public static void updateConsoleFont(Font font) {
        if (font != null) {
            if (lastConsoleFont != null && !lastConsoleFont.equals(font)) {
                LOGGER.info("Console font changed from {} {} to {} {}",
                        lastConsoleFont.getFamily(), lastConsoleFont.getSize(), font.getFamily(), font.getSize());
            } else if (lastConsoleFont == null) {
                LOGGER.info("Setting console font to {} {}", font.getFamily(), font.getSize());
            }
            lastConsoleFont = font;
        } else {
            LOGGER.info("Clearing custom console font settings.");
            lastConsoleFont = null;
            String[] consoleKeys = {
                    "TextPane.font",
                    "TextArea.font",
                    "EditorPane.font",
                    "Monospaced.font"
            };
            for (String key : consoleKeys) {
                UIManager.put(key, null);
            }
            return;
        }

        // Always use FontUIResource for consistency and proper theme scaling
        FontUIResource fontValue = new FontUIResource(font);

        // Update keys that specifically affect text panes and console areas
        String[] consoleKeys = {
                "TextPane.font",
                "TextArea.font",
                "EditorPane.font",
                "Monospaced.font"
        };

        for (String key : consoleKeys) {
            UIManager.put(key, fontValue);
        }
    }

    /**
     * Recursively applies the given font to a component tree.
     */
    public static void applyFontToTree(Component comp, Font font) {
        if (comp == null || font == null)
            return;

        // Use UIResource to prevent the font from being "locked" against future theme
        // changes
        Font fontToApply = (font instanceof FontUIResource) ? font : new FontUIResource(font);

        comp.setFont(fontToApply);

        if (comp instanceof JComponent) {
            JComponent jc = (JComponent) comp;
            if (jc.getBorder() instanceof TitledBorder) {
                ((TitledBorder) jc.getBorder()).setTitleFont(fontToApply);
            }

            if (comp instanceof JTable) {
                JTable table = (JTable) comp;
                if (table.getTableHeader() != null) {
                    table.getTableHeader().setFont(fontToApply);
                }
            }

            if (comp instanceof JProgressBar) {
                ((JProgressBar) comp).setFont(fontToApply);
            }
        }

        if (comp instanceof Container) {
            for (Component child : ((Container) comp).getComponents()) {
                applyFontToTree(child, font);
            }
        }
    }

    /**
     * Centrally applies the current default UI font to a component.
     * Should be called within updateUI() overrides.
     */
    public static void applyDefaultFont(Component comp) {
        Font font = UIManager.getFont("Label.font");
        if (font != null && comp != null) {
            comp.setFont(font instanceof FontUIResource ? font : new FontUIResource(font));
        }
    }

    /**
     * Returns a bold version of the current UI default font.
     */
    public static Font getBoldDefaultFont() {
        Font f = UIManager.getFont("Label.font");
        if (f == null) {
            f = UIManager.getLookAndFeelDefaults().getFont("Label.font");
        }
        if (f == null) {
            f = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        return f.deriveFont(Font.BOLD);
    }

    /**
     * Unified setup for progress bars to ensure font and string painting are
     * consistent.
     */
    public static void setupProgressBar(JProgressBar bar, Font font) {
        if (bar == null)
            return;
        bar.setFont(font != null ? font : UIManager.getFont("Label.font"));
        bar.setStringPainted(true);
    }

    /**
     * Applies a font to a JFreeChart, handling both XYPlot and PiePlot.
     */
    public static void applyFontToChart(JFreeChart chart, Font font) {
        if (chart == null || font == null)
            return;

        if (chart.getPlot() instanceof XYPlot) {
            XYPlot plot = chart.getXYPlot();
            plot.getDomainAxis().setTickLabelFont(font);
            plot.getDomainAxis().setLabelFont(font);
            for (int i = 0; i < plot.getRangeAxisCount(); i++) {
                if (plot.getRangeAxis(i) != null) {
                    plot.getRangeAxis(i).setTickLabelFont(font);
                    plot.getRangeAxis(i).setLabelFont(font);
                }
            }
        } else if (chart.getPlot() instanceof PiePlot) {
            PiePlot plot = (PiePlot) chart.getPlot();
            plot.setLabelFont(font);
        }

        if (chart.getLegend() != null) {
            chart.getLegend().setItemFont(font);
        }
    }

    /**
     * Updates a label's font to include or remove a strikethrough effect.
     */
    public static void updateLabelStrikethrough(JLabel label, boolean visible) {
        if (label == null)
            return;
        Font font = label.getFont();
        Map<TextAttribute, Object> attributes = new HashMap<>(font.getAttributes());
        if (visible) {
            attributes.remove(TextAttribute.STRIKETHROUGH);
        } else {
            attributes.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        }
        label.setFont(font.deriveFont(attributes));
    }
}