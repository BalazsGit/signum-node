package application.utils.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link ConfigurationUtils}' input styling.
 */
@DisplayName("ConfigurationUtils input styling tests")
class ConfigurationUtilsTest {

    @Test
    @DisplayName("styleComboEditor aligns the editable combo's text x with a plain combo's value text x")
    void styleComboEditorMatchesPlainComboTextMargin() throws Exception {
        final int[] margin = new int[2]; // plain text x, editable text x
        final int[] height = new int[2]; // plain pref H, styled editable pref H
        final boolean[] noLnfBorder = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            // The plain combo's value text x: the L&F places the displayed
            // value label at the combo border's left inset, and the text
            // starts at the label's own left margin. One item, so its
            // preferred height is the real one-line value height (an empty
            // model measures as border-only).
            JComboBox<Object> plain = new JComboBox<>(new Object[]{"X"});
            Border plainBorder = plain.getBorder();
            int plainBorderLeft = plainBorder == null ? 0 : plainBorder.getBorderInsets(plain).left;
            @SuppressWarnings("unchecked")
            ListCellRenderer<Object> probeRenderer = plain.getRenderer() != null
                    ? (ListCellRenderer<Object>) plain.getRenderer()
                    : new DefaultListCellRenderer();
            java.awt.Component probeCell = probeRenderer.getListCellRendererComponent(
                    new JList<>(new String[0]), "X", 0, false, false);
            int plainTextX = plainBorderLeft + (probeCell instanceof JComponent jc ? jc.getInsets().left : 0);

            // The editable combo after styling: the text starts at the editor
            // text field's x (walked up to the combo) plus the field's own
            // border insets — measured on the laid-out components.
            JComboBox<Object> editable = new JComboBox<>(new String[0]);
            editable.setEditable(true);
            ConfigurationUtils.styleComboEditor(editable);
            JTextField textField = findEditorTextField((JComponent) editable.getEditor().getEditorComponent());
            editable.setSize(200, 30);
            editable.doLayout();
            int editableTextX = xRelativeTo(editable, textField) + textField.getInsets().left;
            // the field must carry NO L&F border of its own (the combo's own
            // border frames it — an inner bevel would read as a stray frame
            // and a vertical line next to the dropdown arrow): only the
            // padding EmptyBorder may remain.
            noLnfBorder[0] = textField.getBorder() instanceof javax.swing.border.EmptyBorder;
            // the styled editable combo's preferred height must EQUAL the
            // plain combo's (the editor field alone does not necessarily
            // match the value renderer's height)
            editable.setPreferredSize(null);
            // the SINGLE standard height — the one place it is defined
            height[0] = ConfigurationUtils.standardControlHeight();
            height[1] = editable.getPreferredSize().height;

            margin[0] = plainTextX;
            margin[1] = editableTextX;
        });
        assertTrue(margin[0] > 0, "the plain combo's text x must be measurable");
        assertEquals(margin[0], margin[1],
                "the editable combo's text must start at the same x as the plain combo's value text "
                        + "(plain x=" + margin[0] + ", editable x=" + margin[1] + ")");
        assertTrue(noLnfBorder[0],
                "the editor field's border must be a plain EmptyBorder (no L&F field border)");
        assertEquals(height[0], height[1],
                "the styled editable combo's preferred height must equal the plain combo's "
                        + "(plain H=" + height[0] + ", editable H=" + height[1] + ")");
    }

    @Test
    @DisplayName("fixComponentSize and styleComboEditor pin plain and editable combos at the same height")
    void plainAndEditableCombosShareTheStandardHeight() throws Exception {
        final int[] heights = new int[3]; // 0 standard, 1 plain fixed pref H, 2 styled editable pref H
        SwingUtilities.invokeAndWait(() -> {
            // the SINGLE standard height — the one place it is defined
            heights[0] = ConfigurationUtils.standardControlHeight();
            // a plain (non-editable) select, fixed the way the configuration rows do
            JComboBox<Object> plain = new JComboBox<>(new String[] {"Option One", "Option Two"});
            ConfigurationUtils.fixComponentSize(plain);
            heights[1] = plain.getPreferredSize().height;
            // an editable select, padded the way the configuration rows do
            JComboBox<Object> editable = new JComboBox<>(new String[0]);
            editable.setEditable(true);
            ConfigurationUtils.styleComboEditor(editable);
            editable.setPreferredSize(null);
            heights[2] = editable.getPreferredSize().height;
        });
        assertEquals(heights[0], heights[1],
                "fixComponentSize must pin a plain combo at the standard control height "
                        + "(standard H=" + heights[0] + ", plain H=" + heights[1]
                        + ") — the font-metric formula measured a shorter height than a FlatLaf combo, "
                        + "so plain selects used to render lower than their editable siblings");
        assertEquals(heights[0], heights[2],
                "a plain select and an editable select must render at the same (standard) height "
                        + "(standard H=" + heights[0] + ", editable H=" + heights[2] + ")");
    }

    @Test
    @DisplayName("fixComponentSize pins buttons but never squashes one-line controls or containers")
    void fixComponentSizePinsButtonsOnly() throws Exception {
        final java.awt.Dimension[] buttonMax = new java.awt.Dimension[1];
        final java.awt.Dimension[] comboMax = new java.awt.Dimension[1];
        final int[] comboNatural = new int[1];
        final java.awt.Dimension[] fieldMax = new java.awt.Dimension[1];
        final int[] fieldNatural = new int[1];
        final java.awt.Dimension[] containerMax = new java.awt.Dimension[1];
        final int[] containerNatural = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            JButton button = new JButton("Save");
            ConfigurationUtils.fixComponentSize(button);
            buttonMax[0] = button.getMaximumSize();

            JComboBox<Object> combo = new JComboBox<>(new String[]{"One", "Two"});
            comboNatural[0] = combo.getPreferredSize().height;
            ConfigurationUtils.fixComponentSize(combo);
            comboMax[0] = combo.getMaximumSize();

            JTextField field = new JTextField("text");
            ConfigurationUtils.styleInputComponent(field);
            fieldNatural[0] = field.getPreferredSize().height;
            ConfigurationUtils.fixComponentSize(field);
            fieldMax[0] = field.getMaximumSize();

            // the db.URL section's wrapper: a container input — pinning it to a
            // one-line height squashes the whole section (the fields disappear)
            JPanel container = new JPanel();
            container.add(new JTextField("first row"));
            container.add(new JTextField("second row"));
            containerNatural[0] = container.getPreferredSize().height;
            ConfigurationUtils.fixComponentSize(container);
            containerMax[0] = container.getMaximumSize();
        });
        assertTrue(buttonMax[0] != null && buttonMax[0].height > 0 && buttonMax[0].height < 100,
                "a button must keep its font-metric height pin (max = " + buttonMax[0] + ")");
        assertTrue(comboMax[0] == null || comboMax[0].height >= comboNatural[0],
                "a select's max must never clip its natural (font-derived) one-line height "
                        + "(max = " + comboMax[0] + ", natural H=" + comboNatural[0]
                        + ") — a one-line pin would freeze it at the built-with font size and clip "
                        + "larger fonts");
        assertTrue(fieldMax[0] == null || fieldMax[0].height >= fieldNatural[0],
                "a text input's max must never clip its unified (standard) height "
                        + "(max = " + fieldMax[0] + ", natural H=" + fieldNatural[0] + ")");
        assertTrue(containerMax[0] == null || containerMax[0].height >= containerNatural[0],
                "a container input's max must never squash its multi-line content "
                        + "(max = " + containerMax[0] + ", natural H=" + containerNatural[0] + ")");
    }

    @Test
    @DisplayName("unifyTitledBoxHeights equalizes the titled boxes at the standard combo height")
    void unifyTitledBoxHeightsEqualizesBoxes() throws Exception {
        final int[] size = new int[6];
        // 0 status pref H, 1 search pref H, 2 plain combo H,
        // 3 search pref W (before), 4 search pref W (after), 5 search pref H (match UI shown)
        SwingUtilities.invokeAndWait(() -> {
            // The same boxes the configuration's search row uses: the
            // "Show values" checkbox group and the "Search" box.
            CheckboxGroupPanel status = new CheckboxGroupPanel("Show values");
            status.addCheckbox("Unsaved");
            status.addCheckbox("Unsaved Applied");
            status.addCheckbox("Saved");
            status.addCheckbox("Saved Applied");
            SearchMatchPanel search = new SearchMatchPanel(null);
            size[3] = search.getPreferredSize().width;
            ConfigurationUtils.unifyTitledBoxHeights(status, search);
            size[0] = status.getPreferredSize().height;
            size[1] = search.getPreferredSize().height;
            // the SINGLE standard height — the one place it is defined
            size[2] = ConfigurationUtils.standardControlHeight();
            size[4] = search.getPreferredSize().width;
            // the match UI that starts hidden must FIT the unified height
            // once shown (no clipping, no growth).
            search.setMatchIndicatorText("1/23");
            search.setChevronsVisible(true);
            size[5] = search.getPreferredSize().height;
        });
        assertEquals(size[0], size[1],
                "the \"Show values\" and \"Search\" boxes must have the same (unified) preferred height "
                        + "(show-values H=" + size[0] + ", search H=" + size[1] + ")");
        assertTrue(size[1] >= size[2],
                "the unified box height must not be lower than the standard combo height "
                        + "(box H=" + size[1] + ", combo H=" + size[2] + ")");
        assertEquals(size[3], size[4],
                "unifying the HEIGHT must not pin the search box's width (it stays content-driven)");
        assertEquals(size[1], size[5],
                "the search box's hidden match counter / chevrons must fit the unified height "
                        + "(unified H=" + size[1] + ", shown H=" + size[5] + ")");
    }

    /** The text field of a combo editor (the editor component itself with
     *  the Basic L&F, its child text field with the Metal one). */
    private static JTextField findEditorTextField(JComponent c) {
        if (c instanceof JTextField tf) {
            return tf;
        }
        if (c instanceof java.awt.Container cont) {
            for (java.awt.Component child : cont.getComponents()) {
                if (child instanceof JTextField tf) {
                    return tf;
                }
            }
        }
        throw new AssertionError("no editor text field found");
    }

    /** The x of {@code child} relative to {@code root} (parent chain). */
    private static int xRelativeTo(JComponent root, JComponent child) {
        int x = 0;
        for (JComponent k = child; k != null && k != root; k = k.getParent() instanceof JComponent p ? p : null) {
            x += k.getBounds().x;
        }
        return x;
    }
}
