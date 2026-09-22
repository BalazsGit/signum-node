package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;

import application.utils.gui.SearchMatchLabel;
import application.utils.gui.SearchMatchPanel;
import application.module.node.gui.configuration.NodeConfigurationPanel.ComboSearchHighlightRenderer;
import application.module.node.gui.configuration.NodeConfigurationPanel.PropertyRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for extending the live configuration search to the
 * SELECT menus: the value currently configured in a (non-editable) combo
 * box is visible to the user, so a query in that value must match the row —
 * while the (not visible) other dropdown items must NOT match — and the
 * match band must be painted behind the matched part of the selected value
 * (the combo's renderer is swapped for a band-painting label and restored
 * when the search is cleared).
 */
@DisplayName("NodeConfigurationPanel select-value search tests")
class NodeConfigurationPanelComboSearchTest {

    private static final long INIT_TIMEOUT_MS = 60_000;

    @Test
    @DisplayName("a select row matches on its configured (selected) value only")
    void selectSelectedValue_isSearchable() {
        JComboBox<String> combo = new JComboBox<>(new String[] {"modern", "legacy", "off"});
        combo.setSelectedItem("modern");
        PropertyRow row = new PropertyRow(null, "API Documentation Mode", null, 0);
        row.label = new JLabel("API Documentation Mode");
        row.input = combo;

        // "odern" occurs in the SELECTED value only (not in the label name)
        assertTrue(NodeConfigurationPanel.isRowSearchMatch(row, "odern".toLowerCase(Locale.ROOT)),
                "the configured select value must be searchable");
        // The not-selected dropdown items are not visible: they must not match
        assertFalse(NodeConfigurationPanel.isRowSearchMatch(row, "legacy".toLowerCase(Locale.ROOT)),
                "a non-selected dropdown item must not match");
        assertFalse(NodeConfigurationPanel.isRowSearchMatch(row, "off".toLowerCase(Locale.ROOT)),
                "a non-selected dropdown item must not match");
        // The label name matching is unchanged
        assertTrue(NodeConfigurationPanel.isRowSearchMatch(row, "documentation".toLowerCase(Locale.ROOT)));
    }

    @Test
    @DisplayName("the select band renderer paints the band behind the matched characters only")
    void selectBandRenderer_paintsExactRange() {
        Color bandColor = new Color(0xC8, 0x64, 0xE0);
        ComboSearchHighlightRenderer renderer = new ComboSearchHighlightRenderer();
        renderer.setBand("mod", bandColor);
        JList<String> list = new JList<>(new String[] {"modern", "legacy", "off"});
        renderer.getListCellRendererComponent(list, "modern", 0, false, false);
        assertTrue(renderer.hasHighlight(), "the matching item must carry the band range");

        int w = 200, h = 30;
        renderer.setSize(w, h);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        renderer.paint(g);
        FontMetrics fm = g.getFontMetrics(renderer.getFont());
        g.dispose();

        // The label's default alignment (horizontal: left, vertical: lead)
        // puts the text at the left inset, vertically centered.
        Insets in = renderer.getInsets();
        int textX = in.left;
        int textY = in.top + (h - in.top - in.bottom - fm.getHeight()) / 2;
        int bandEnd = textX + fm.stringWidth("mod");

        assertTrue(containsColor(image, textX, bandEnd, textY, textY + fm.getHeight(), bandColor),
                "the band must be painted behind the matched characters");
        int afterStart = Math.min(w, bandEnd + 2);
        assertFalse(containsColor(image, afterStart, Math.min(w, afterStart + 8), textY,
                textY + fm.getHeight(), bandColor),
                "the band must not extend past the matched characters");
    }

    @Test
    @DisplayName("the select band renderer shows no band on items without the query")
    void selectBandRenderer_noBandOnOtherItems() {
        ComboSearchHighlightRenderer renderer = new ComboSearchHighlightRenderer();
        renderer.setBand("mod", new Color(0xC8, 0x64, 0xE0));
        JList<String> list = new JList<>(new String[] {"modern", "legacy", "off"});
        renderer.getListCellRendererComponent(list, "legacy", 1, false, false);
        assertEquals("legacy", renderer.getText());
        assertFalse(renderer.hasHighlight(), "no band may be painted on a non-matching item");
    }

    @Test
    @DisplayName("searching a select's configured value lists the row, bands the value and restores on clear")
    void select_selectedValueSearch_andHighlight() throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Object[] panelRef = new Object[1];
        final Object[] ownerRef = new Object[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                // Same icon-font registration the application performs at
                // startup (AppearanceModule#init) so IconFontSwing resolves.
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("combo-search-test-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null,
                        null, "combo-search-test");
                owner.add(panel);
                owner.setSize(900, 700);
                owner.setVisible(true);
                panelRef[0] = panel;
                ownerRef[0] = owner;
            } catch (Throwable t) {
                error.set(t);
            }
        });
        assertNotNull(panelRef[0], "the configuration panel must be constructable: " + error.get());

        try {
            // Wait for the async UI construction (initUI on the EDT) to finish.
            final Object[] ready = new Object[1];
            long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && ready[0] == null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        Object rows = readDeclaredField(panelRef[0], "allPropertyRows");
                        if (rows instanceof List<?> list && !list.isEmpty()) {
                            ready[0] = true;
                        }
                    } catch (Exception ignore) {
                        // fields not ready yet — keep polling
                    }
                });
                if (ready[0] == null) {
                    Thread.sleep(50);
                }
            }
            assertNotNull(ready[0], "the property rows must be built within " + INIT_TIMEOUT_MS + " ms");

            final List<String> out = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    // Find a non-editable select row (the test profile has a
                    // few: API Documentation Mode, the SQLite select properties).
                    JComboBox<?> combo = null;
                    Object row = null;
                    for (Object candidate : (List<?>) readDeclaredField(panelRef[0], "allPropertyRows")) {
                        Object input = readField(candidate, "input");
                        if (input instanceof JComboBox<?> cb && !cb.isEditable() && cb.getItemCount() > 0) {
                            combo = cb;
                            row = candidate;
                            break;
                        }
                    }
                    assumeTrue(combo != null, "the test profile must have a non-editable select row");

                    // The configured value of the select, and a query that
                    // occurs in that value and NOWHERE in the row's label
                    // name or key line (so a match can only come from the value).
                    if (combo.getSelectedItem() == null) {
                        combo.setSelectedItem(combo.getItemAt(0));
                    }
                    String query = String.valueOf(combo.getSelectedItem());
                    String labelText = (String) readField(row, "labelText");
                    Object label = readField(row, "label");
                    String keyText = label instanceof SearchMatchLabel sl ? sl.getKeyText() : null;
                    assumeTrue(!containsIgnoreCase(labelText, query)
                            && (keyText == null || !containsIgnoreCase(keyText, query)),
                            "the query must occur only in the configured select value");

                    Object originalRenderer = combo.getRenderer();
                    SearchMatchPanel searchPanel =
                            (SearchMatchPanel) readDeclaredField(panelRef[0], "searchMatchPanel");

                    // 1. Search the configured value: the row must be listed
                    //    and its combo renderer swapped for the band renderer.
                    searchPanel.getSearchField().setText(query);
                    List<?> matches = (List<?>) readDeclaredField(panelRef[0], "searchMatches");
                    if (!matches.contains(row)) {
                        out.add("a query in the configured select value must list the row");
                    }
                    if (!(combo.getRenderer() instanceof ComboSearchHighlightRenderer)) {
                        out.add("the matching select must be rendered by the band renderer (got: "
                                + combo.getRenderer().getClass().getSimpleName() + ")");
                    }

                    // 2. Clearing the search restores the original renderer.
                    searchPanel.getSearchField().setText("");
                    matches = (List<?>) readDeclaredField(panelRef[0], "searchMatches");
                    if (!matches.isEmpty()) {
                        out.add("clearing the search must empty the match list");
                    }
                    if (!combo.getRenderer().equals(originalRenderer)) {
                        out.add("clearing the search must restore the select's original renderer");
                    }
                } catch (Throwable t) {
                    out.add("unexpected error: " + t);
                }
            });
            assertTrue(out.isEmpty(), String.join("; ", out));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (ownerRef[0] instanceof JFrame frame) {
                    frame.dispose();
                }
            });
        }
    }


    private static boolean containsIgnoreCase(String text, String query) {
        return text != null && query != null && !query.isEmpty()
                && text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static boolean containsColor(BufferedImage image, int x0, int x1, int y0, int y1, Color color) {
        for (int x = Math.max(0, x0); x < Math.min(image.getWidth(), x1); x++) {
            for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++) {
                if (new Color(image.getRGB(x, y), true).equals(color)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object readField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object readDeclaredField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new AssertionError("field not found: " + name);
    }
}