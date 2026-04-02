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

    /**
     * Updates UIManager font keys and applies them to the current theme.
     * If not using FlatLaf, it "locks" the font by using a plain Font instead of
     * FontUIResource.
     */
    public static void updateUIManager(Font font) {
        LookAndFeel laf = UIManager.getLookAndFeel();
        boolean isFlatLaf = laf instanceof FlatLaf;

        if (font != null) {
            LOGGER.info("Updating UIManager font keys. Target font: {} ({})", font, font.getClass().getName());
        } else {
            LOGGER.info("Clearing custom UIManager font keys.");
        }

        // For Nimbus/Standard L&F, we use a plain Font to prevent the L&F from
        // overriding it later.
        Object fontValue = (font == null) ? null
                : (isFlatLaf ? new FontUIResource(font)
                        : new Font(font.getFamily(), font.getStyle(), font.getSize()));

        UIManager.put("defaultFont", fontValue);

        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        Set<Object> keys = new HashSet<>(defaults.keySet());
        keys.addAll(UIManager.getDefaults().keySet());
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
        LOGGER.info("Updated {} font keys in UIManager.", count);
    }

    /**
     * Recursively applies the given font to a component tree.
     */
    public static void applyFontToTree(Component comp, Font font) {
        if (comp == null || font == null)
            return;

        boolean isFlatLaf = UIManager.getLookAndFeel() instanceof FlatLaf;
        Font fontToApply = isFlatLaf ? new FontUIResource(font)
                : new Font(font.getFamily(), font.getStyle(), font.getSize());

        comp.setFont(fontToApply);

        if (!(comp instanceof JPanel) && !(comp instanceof Box) && !(comp instanceof JLayeredPane)) {
            LOGGER.info("Applied font {} to {} [{}]", fontToApply.getSize(), comp.getClass().getSimpleName(),
                    (comp.getName() != null ? comp.getName() : "unnamed"));
        }

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