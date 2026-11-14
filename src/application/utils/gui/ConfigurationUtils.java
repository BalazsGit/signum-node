package application.utils.gui;

import application.module.appearance.AppearanceProfile;
import application.module.node.Signum;
import application.module.node.gui.configuration.LoggerProfile;
import application.module.node.profile.NodeProfile;
import application.utils.config.ConfigPaths;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;
import application.utils.config.ReservedNames;
import application.utils.io.PathUtils;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import com.google.gson.JsonElement;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.*;
import java.io.FileInputStream;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConfigurationUtils {

    /**
     * THE single, uniformly adjustable gap between an input field and the
     * action icon sitting next to it — the list editors' "+" add button, the
     * per-item trash icon, the property row's help ("?") icon and the logging
     * profile rows' trash icon. (Password fields reveal/hide through FlatLaf's
     * built-in reveal button — enabled globally by GuiManager#applyDefaultsAfterLaf
     * — and need no adjacent control.) Every one of these field-adjacent icon
     * gaps reads from this constant, so the distance is uniform across the
     * configuration and logging panels and tunable in exactly one place.
     */
    public static final int INPUT_ACTION_GAP = 4;

    /**
     * THE single, shared MigLayout constraint for the horizontal separator that
     * ends a configuration property row — it spans the full content width
     * ({@code span}), grows horizontally ({@code growx}), wraps to the next row
     * ({@code wrap}) and keeps a uniform 2px top/bottom gap. Read from this
     * constant by every configuration panel (node, database) so the row
     * separators look identical everywhere and are tunable in one place.
     */
    public static final String PROPERTY_SEPARATOR_CONSTRAINTS = "span, growx, wrap, gaptop 2, gapbottom 2";

    /**
     * THE single, shared column count (preferred width) of the search field in a
     * configuration panel's search box. Read from this constant by
     * {@link #createSearchField} and the shared {@link SearchMatchPanel} so every
     * panel's search box has the same width and is tunable in one place.
     */
    public static final int SEARCH_FIELD_COLUMNS = 16;

    /**
     * Validates a TCP port value for the inline configuration editors.
     *
     * @param label    the setting label to include in the error
     * @param rawValue the value currently shown by the editor
     * @return a user-facing error, or {@code null} when the value is valid
     */
    public static String portValidationError(String label, String rawValue) {
        int port;
        try {
            port = Integer.parseInt(rawValue == null ? "" : rawValue.trim());
        } catch (NumberFormatException e) {
            return "Invalid " + label + ": enter a whole number from 1 to 65535.";
        }
        if (port < 1 || port > 65535) {
            return "Invalid " + label + ": value must be between 1 and 65535.";
        }
        return null;
    }

    /**
     * Styles input components consistently using the global label font and the
     * unified one-line control metrics.
     * <p>
     * For text inputs (fields / password fields) this is THE single place the
     * regular configuration input's look is defined — the SAME policy the
     * selects use, so a text field and a select of the same row render
     * identically (and the db.URL section's fields no longer differ from
     * every other configuration field):
     * <ul>
     *   <li><b>border</b>: the L&F's own slim field border (the same 1px
     *       border the regular inputs carry) — no extra chrome,</li>
     *   <li><b>height</b>: padded to {@link #standardControlHeight()} — the
     *       plain combo's MEASURED height, so the field grows with the font
     *       exactly like the selects (re-call this after a font / L&F change
     *       to re-derive the padding from the new metrics),</li>
     *   <li><b>left margin</b>: the plain combo's value-text x
     *       ({@link #plainComboTextX()}) — the same text start the state-aware
     *       editable selects align to.</li>
     * </ul>
     *
     * @param comp The component to style.
     */
    public static void styleInputComponent(JComponent comp) {
        comp.setFont(UIManager.getFont("Label.font"));
        if (comp instanceof JTextField || comp instanceof JPasswordField) {
            Border fieldBorder = UIManager.getBorder("TextField.border");
            // Measure the field's natural height with the L&F border ALONE,
            // then pad the difference to the standard (the bottom edge takes
            // the rounding remainder) — measured, not modeled, so any L&F's
            // border / font interplay lands exactly.
            comp.setBorder(fieldBorder);
            int fieldLeft = fieldBorder == null ? 0 : fieldBorder.getBorderInsets(comp).left;
            int left = Math.max(0, plainComboTextX() - fieldLeft);
            padToStandardHeight(comp, fieldBorder, left);
        }
    }

    /**
     * Creates a search / filter text field with the FlatLaf search-field
     * look — the SAME recipe the FlatLaf demo's "Basic components" panel
     * uses for its search box: FlatLaf's leading magnifier icon (tinted
     * with the field's foreground color automatically) plus the given
     * placeholder text. THE single place this look is defined, so every
     * configuration search field (the node / logging / database / color
     * panels and the unified {@link SearchMatchPanel} find box) renders
     * identically.
     *
     * @param placeholderText the placeholder shown while the field is empty
     *                        (null/blank: no placeholder)
     */
    public static JTextField createSearchField(String placeholderText) {
        JTextField field = new JTextField();
        // Uniform search-box width (the shared column count) so every panel's
        // search field has the same size — see SEARCH_FIELD_COLUMNS.
        field.setColumns(SEARCH_FIELD_COLUMNS);
        applySearchFieldStyle(field, placeholderText);
        return field;
    }

    /**
     * Applies the FlatLaf search-field look to an existing field (see
     * {@link #createSearchField}).
     * <p>
     * Like {@link #styleInputComponent} the field gets the label font and
     * the standard control height, but WITHOUT the combo-aligned left
     * margin: the leading icon must sit flush at the field's left edge.
     *
     * @param placeholderText the placeholder shown while the field is empty
     *                        (null/blank: no placeholder)
     */
    public static void applySearchFieldStyle(JTextField field, String placeholderText) {
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, new FlatSearchIcon());
        if (placeholderText != null && !placeholderText.isBlank()) {
            field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholderText);
        }
        field.setFont(UIManager.getFont("Label.font"));
        Border fieldBorder = UIManager.getBorder("TextField.border");
        field.setBorder(fieldBorder);
        padToStandardHeight(field, fieldBorder, 0);
    }

    /**
     * Pads the field's (already applied) L&F border so its preferred height
     * equals the standard one-line control height — the vertical half of
     * {@link #styleInputComponent}, extracted because the search-field
     * variant needs the same height but a flush left edge.
     */
    private static void padToStandardHeight(JComponent field, Border fieldBorder, int leftInset) {
        int naturalH = field.getPreferredSize().height;
        int extra = Math.max(0, standardControlHeight() - naturalH);
        int top = extra / 2;
        int bottom = extra - top;
        field.setBorder(BorderFactory.createCompoundBorder(fieldBorder,
                BorderFactory.createEmptyBorder(top, leftInset, bottom, 6)));
    }

    /**
     * Cache for the L&F-derived metrics below ({@link #standardControlHeight()}
     * and {@link #plainComboTextX()}). Each metric is probed by building a
     * throwaway one-item JComboBox — on its own a full L&F install (editor
     * field, document, renderer), which must NOT be repeated for every input
     * row of a panel (NodeConfigurationPanel.initUI calls these ~200+ times).
     * The metrics are pure functions of the current L&F and default control
     * font; a font / L&F change yields a new key and exactly one re-probe.
     * Accessed on the EDT only (same contract as the probe methods).
     */
    private static String metricsCacheKey;
    private static int cachedStandardControlHeight;
    private static int cachedPlainComboTextX;

    private static void ensureMetricsCached() {
        // The probe combo's measured height / text-start margin are driven by the
        // combo's own font (ComboBox.font) plus the L&F's border and renderer —
        // key the cache on exactly those so a font or L&F change re-probes once.
        String key = UIManager.getLookAndFeel().getClass().getName()
                + '|' + UIManager.getFont("ComboBox.font");
        if (key.equals(metricsCacheKey)) {
            return;
        }
        // one item, so the combo's preferred HEIGHT is the real one-line value
        // height (an empty model measures as border-only)
        JComboBox<Object> plain = new JComboBox<>(new Object[]{ "X" });
        cachedStandardControlHeight = plain.getPreferredSize().height;
        Border plainBorder = plain.getBorder();
        int plainBorderLeft = plainBorder == null ? 0 : plainBorder.getBorderInsets(plain).left;
        @SuppressWarnings("unchecked")
        ListCellRenderer<Object> probeRenderer = plain.getRenderer() != null
                ? (ListCellRenderer<Object>) plain.getRenderer()
                : new DefaultListCellRenderer();
        java.awt.Component probeCell = probeRenderer.getListCellRendererComponent(
                new JList<>(new String[0]), "X", 0, false, false);
        cachedPlainComboTextX = plainBorderLeft + (probeCell instanceof JComponent jc ? jc.getInsets().left : 0);
        metricsCacheKey = key;
    }

    /**
     * Where a plain (non-editable) combo of the current L&F starts its value
     * text: the combo border's left inset plus the renderer cell's left
     * margin — probed on throwaway components (one item, so its preferred
     * height is the real one-line value height).
     * <p>
     * THE single place that text-start margin is defined: the editable
     * selects ({@link #styleComboEditor}), the text inputs
     * ({@link #styleInputComponent}) and the JDBC section's frameless URL
     * preview all align their text to it.
     */
    public static int plainComboTextX() {
        ensureMetricsCached();
        return cachedPlainComboTextX;
    }

    /**
     * Styles the EDITOR text field of an editable combo. With the Metal L&F the
     * combo's editor component is a panel WRAPPING the text field, so
     * {@link #styleInputComponent} applied to the editor component never
     * reaches the text field itself (default font / border would remain).
     * <p>
     * The field carries NO border of its own: the combo's own L&F border
     * already frames the whole control, exactly like a regular
     * (non-editable) combo — the field's own bevel would read as a stray
     * inner frame (lines around the text, a vertical line by the dropdown
     * arrow) that the built-in combos do not have.
     * <p>
     * The left padding is computed so the text starts at the SAME x as the
     * value text of a regular combo of the current L&F. Both x offsets are
     * MEASURED for real (a fixed assumption like "the renderer adds 2 px"
     * indents the editable text further than the built-in combos):
     * <ul>
     *   <li>the TARGET is the plain combo's border left inset plus its
     *       renderer cell's left margin (that is where Basic L&Fs place the
     *       displayed value label),</li>
     *   <li>the CURRENT position of the editor text field is found by laying
     *       the combo out off-screen and walking the field's x up to the
     *       combo (the L&F decides where the editor sits — inside the combo
     *       border, behind a wrapper panel, ...) plus the field's own border
     *       insets.</li>
     * </ul>
     * The difference is absorbed by the empty border's left inset. (The field
     * has no L&F border at all, so its own insets are 0 and the current x is
     * simply the field's layout offset; the clamp to 0 covers an exotic L&F
     * that places the editor deeper than the plain combo's text.)
     * <p>
     * The top / bottom padding is computed so the combo's own preferred
     * HEIGHT equals the plain combo's preferred height EXACTLY (an editable
     * combo's height is driven by its editor field, a plain combo's by its
     * value renderer — the two do not necessarily agree): the missing pixels
     * are MEASURED and split over both edges (the bottom edge takes the
     * rounding remainder), with one correction pass for an L&F that absorbs
     * part of the padding.
     */
    public static void styleComboEditor(JComboBox<?> combo) {
        JComponent editorComponent = (JComponent) combo.getEditor().getEditorComponent();
        editorComponent.setFont(UIManager.getFont("Label.font"));
        JTextField textField = findEditorTextField(editorComponent);
        if (textField == null) {
            return;
        }
        textField.setFont(UIManager.getFont("Label.font"));
        // TARGET: where a plain (non-editable) combo of this L&F starts its
        // value text — the shared, single-place measurement (the text inputs
        // of {@link #styleInputComponent} align to the very same x).
        int targetX = plainComboTextX();
        // CURRENT: no field border (see above) → the field's own insets are 0
        // and its x (walked up to the combo, under the L&F's own layout) IS
        // where its text starts.
        textField.setBorder(null);
        combo.setSize(200, 30);
        combo.doLayout();
        int currentX = xRelativeTo(combo, textField);
        // LEFT: the empty border pads the text up to the plain combo's text
        // x (and provides the regular input's right look). Clamped: a layout
        // offset deeper than the target (an exotic L&F) just gets 0.
        int left = Math.max(0, targetX - currentX);
        // TOP / BOTTOM: an editable combo's height is driven by its editor
        // field, while a plain combo's is driven by its value renderer — the
        // two do not necessarily agree (a font override in particular makes
        // the field shorter, so the editable combo renders LOWER than its
        // non-editable siblings). Pad the field vertically so the combo's
        // OWN preferred height equals the plain combo's — measured, not
        // modeled, so every L&F's border / button / renderer interplay is
        // handled as-is — and EXACTLY: the missing pixels are computed from
        // the measured shortfall and split over both edges (the bottom edge
        // takes the rounding remainder). The former grow-until-reached loop
        // broke at the first padding that reached the target and could
        // overshoot by a pixel or two, so editable selects rendered TALLER
        // than their plain siblings. Where the L&F drives the height from
        // something the field cannot influence, the math just lands short by
        // that fixed offset — the field cannot do more.
        // the SINGLE standard height (one-place definition — the plain
        // combo above has one item, so it measures exactly this value)
        int plainH = standardControlHeight();
        textField.setBorder(BorderFactory.createEmptyBorder(0, left, 0, 6));
        combo.setPreferredSize(null);
        int base = combo.getPreferredSize().height;
        int top = 0;
        int bottom = 0;
        int delta = plainH - base;
        if (delta > 0) {
            top = delta / 2;
            bottom = delta - top;
        }
        textField.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, 6));
        combo.setPreferredSize(null);
        int h = combo.getPreferredSize().height;
        if (h < plainH) {
            // an L&F that absorbed part of the padding (a wrapper inset, a
            // fixed arrow-button height, ...) — add the missing pixels to
            // the bottom edge and re-measure once
            bottom += plainH - h;
            textField.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, 6));
            combo.setPreferredSize(null);
        }
        // leave the preferred size UNSET: the reads above cached a size
        // measured at the (possibly empty) creation-time model — the panel
        // must compute the width from the live model at layout time.
        combo.setPreferredSize(null);
    }

    /**
     * THE standard one-line control height of the current L&F — the
     * preferred height of a plain, non-editable, one-item combo, measured
     * LIVE, so a font / L&F change is picked up on the next call.
     * <p>
     * This is the SINGLE place the "standard combo height" is defined:
     * both the editable dropdowns ({@link #styleComboEditor}) and the
     * compact titled boxes ({@link #unifyTitledBoxHeights}) are unified
     * against this one value — if the standard metric ever changes, only
     * this method is touched, no caller and no panel is.
     *
     * @return the standard one-line control height in pixels (EDT)
     */
    public static int standardControlHeight() {
        ensureMetricsCached();
        return cachedStandardControlHeight;
    }

    /**
     * Unifies the preferred HEIGHT of a group of compact titled boxes
     * ({@link TitledBorder} panels — e.g. the "Show values" checkbox group
     * and the "Search" box of the configuration's search row) so the boxes of
     * one row are the same height and track the standard control height — the
     * box-level counterpart of {@link #styleComboEditor}, which unifies the
     * dropdowns the same way.
     * <p>
     * The unified height is the LARGEST of
     * <ul>
     *   <li>each box's own natural preferred height — measured with ALL of
     *       its children temporarily VISIBLE, so a child that is hidden at
     *       build time but shown later (the search match counter and chevron
     *       buttons) can never outgrow the box and get clipped;</li>
     *   <li>{@link #standardControlHeight()} — the standard one-line control
     *       height the dropdowns are unified to (the single place it is
     *       defined).</li>
     * </ul>
     * The target is rounded up to an even pixel height, avoiding one-pixel
     * differences between otherwise equivalent rows when a look-and-feel
     * reports fractional font/border metrics differently.
     * A box shorter than the unified height is padded with a BOTTOM
     * EmptyBorder INSIDE its TitledBorder (the L&F's TitledBorder ignores
     * the inner border's top inset — the title owns the top edge — but
     * counts its bottom inset, so all padding goes there): the box's WIDTH
     * keeps growing with its content, and the box's border remains a plain
     * {@link TitledBorder}. A box that is naturally taller (e.g. a wrapped
     * checkbox row) is kept as-is — nothing is ever clipped.
     * <p>
     * The heights are MEASURED at the call, so a font or L&F change that
     * rebuilds the panel unifies the boxes again with the new metrics — the
     * boxes change identically to the dropdowns.
     *
     * @param boxes the titled boxes of one row to unify (EDT)
     */
    public static void unifyTitledBoxHeights(JComponent... boxes) {
        if (boxes == null || boxes.length == 0) {
            return;
        }
        // the SINGLE standard height — see standardControlHeight
        int target = standardControlHeight();
        int[] natural = new int[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            // the padding decision uses the box's CURRENT natural height
            // (hidden children included in neither) — the TARGET must cover
            // the all-children-visible height, so a later-shown child (the
            // search chevrons) can never outgrow the unified height.
            natural[i] = boxes[i].getPreferredSize().height;
            int allVisible = naturalHeightWithAllChildrenVisible(boxes[i]);
            if (allVisible > target) {
                target = allVisible;
            }
        }
        if ((target & 1) != 0) {
            target++;
        }
        for (int i = 0; i < boxes.length; i++) {
            int extra = target - natural[i];
            if (extra <= 0) {
                continue;
            }
            // ALL the padding goes on the BOTTOM: a TitledBorder's own insets
            // computation ignores the inner border's TOP inset (the title owns
            // the top edge) but does count bottom / left / right — measured,
            // not modeled, on the L&F's TitledBorder.
            Border inner = BorderFactory.createEmptyBorder(0, 0, extra, 0);
            Border border = boxes[i].getBorder();
            if (border instanceof TitledBorder titled) {
                titled.setBorder(BorderFactory.createCompoundBorder(titled.getBorder(), inner));
            } else {
                boxes[i].setBorder(BorderFactory.createCompoundBorder(border, inner));
            }
        }
    }

    /**
     * The box's natural preferred height with EVERY child visible — the
     * height a child that starts hidden (the search match counter and
     * chevrons) would demand once shown. The original visibilities are
     * restored.
     */
    private static int naturalHeightWithAllChildrenVisible(JComponent box) {
        if (!(box instanceof java.awt.Container container)) {
            return box.getPreferredSize().height;
        }
        List<java.awt.Component> hidden = new ArrayList<>();
        for (java.awt.Component child : container.getComponents()) {
            if (!child.isVisible()) {
                hidden.add(child);
            }
        }
        try {
            for (java.awt.Component child : hidden) {
                child.setVisible(true);
            }
            return box.getPreferredSize().height;
        } finally {
            for (java.awt.Component child : hidden) {
                child.setVisible(false);
            }
        }
    }

    /**
     * The x of {@code child} relative to {@code root} (walking the parent
     * chain) — for a component laid out inside {@code root}.
     */
    private static int xRelativeTo(JComponent root, JComponent child) {
        int x = 0;
        for (JComponent k = child; k != null && k != root; k = k.getParent() instanceof JComponent p ? p : null) {
            x += k.getBounds().x;
        }
        return x;
    }

    /** Finds the text field used by a combo editor, including wrapped editors. */
    public static JTextField findEditorTextField(Component c) {
        if (c instanceof JTextField tf) {
            return tf;
        }
        if (c instanceof Container cont) {
            for (Component child : cont.getComponents()) {
                JTextField textField = findEditorTextField(child);
                if (textField != null) {
                    return textField;
                }
            }
        }
        return null;
    }

    /**
     * Styles a read-only JDBC URL preview as a readable "code chip": monospaced text on
     * a subtle contrasting background with a 1px border and padding. The background is
     * derived from the current L&F text background so it works in both light and dark
     * themes.
     *
     * @param field The preview text field to style.
     */
    public static void styleJdbcPreview(JTextField field) {
        field.setEditable(false);
        field.setOpaque(true);
        Color base = UIManager.getColor("TextField.background");
        if (base == null)
            base = Color.WHITE;
        double luminance = 0.299 * base.getRed() + 0.587 * base.getGreen() + 0.114 * base.getBlue();
        Color adjust = luminance > 128 ? Color.BLACK : Color.WHITE;
        int mix = 12;
        field.setBackground(new Color(
                (base.getRed() * (255 - mix) + adjust.getRed() * mix) / 255,
                (base.getGreen() * (255 - mix) + adjust.getGreen() * mix) / 255,
                (base.getBlue() * (255 - mix) + adjust.getBlue() * mix) / 255));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GuiColors.getSeparator(), 1),
                new EmptyBorder(4, 8, 4, 8)));
        field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(11, field.getFont().getSize())));
    }

    /**
     * Calculates and sets a fixed height for a component based on font metrics and
     * standard icon sizes to ensure consistent UI layout across different systems.
     * <p>
     * One-line controls are the exception: they size themselves from the LIVE
     * metrics — a plain combo's natural height IS {@link #standardControlHeight()}
     * (the very metric a plain combo measures), an editable one is padded to that
     * same value by {@link #styleComboEditor} and a text input by
     * {@link #styleInputComponent}. Pinning them would freeze the control at the
     * font size it was built with (a later font growth would clip its text
     * instead of growing the control), so for those only stale pins are cleared.
     * <p>
     * Containers are the second exception: a panel input (the db.URL section's
     * wrapper) keeps its NATURAL multi-line height — pinning it to a one-line
     * height squashes its whole content (the section's fields disappear).
     *
     * @param comp The component whose size should be fixed.
     */
    public static void fixComponentSize(JComponent comp) {
        comp.setPreferredSize(null);
        comp.setMinimumSize(null);
        comp.setMaximumSize(null);

        if (comp instanceof JPanel
                || comp instanceof JComboBox
                || comp instanceof JTextField || comp instanceof JPasswordField) {
            return;
        }

        // Buttons / checkboxes: the font-metric pin (unchanged).
        Font font = comp.getFont() != null ? comp.getFont() : UIManager.getFont("Label.font");
        FontMetrics fm = comp.getFontMetrics(font);
        int fontHeight = fm.getHeight();

        // Ensure height is at least enough for the help icons used in the rows (usually
        // 16-18px)
        int iconHeight = Math.round(GuiConstants.getHelpIconSize());
        int targetHeight = Math.max(fontHeight + 10, iconHeight + 6);

        Dimension currentPref = comp.getPreferredSize();
        Dimension size = new Dimension(currentPref.width + 4, targetHeight);
        comp.setPreferredSize(size);
        comp.setMinimumSize(size);
        comp.setMaximumSize(new Dimension(Short.MAX_VALUE, targetHeight));
    }

    /**
     * Creates a custom {@link ListCellRenderer} for profile selection combo boxes.
     * Highlights the running and active profiles using bold fonts and
     * status-specific colors.
     *
     * @param runningProfileSupplier Supplier for the name of the currently running
     *                               profile.
     * @param activeProfileSupplier  Supplier for the name of the currently active
     *                               (applied) profile.
     * @return A custom cell renderer.
     */
    public static ListCellRenderer<Object> createProfileComboBoxRenderer(Supplier<String> runningProfileSupplier,
            Supplier<String> activeProfileSupplier) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String valStr = (value != null) ? value.toString().trim() : "";
                if (!valStr.isEmpty()) {
                    String running = runningProfileSupplier.get();
                    String active = activeProfileSupplier.get();
                    boolean isRunning = valStr.equals(running);
                    boolean isActive = valStr.equals(active);

                    if (isRunning || isActive) {
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    }
                    if (!isSelected) {
                        if (isRunning) {
                            c.setForeground(GuiColors.getApplied());
                        } else if (isActive) {
                            c.setForeground(GuiColors.getSaved());
                        } else {
                            c.setForeground(GuiColors.getUnsaved());
                        }
                    }
                }
                return c;
            }
        };
    }

    /**
     * Saves a set of properties to a file while attempting to preserve the existing
     * formatting and comments found in the original file.
     *
     * @param file        The path to the properties file.
     * @param props       The properties to save.
     * @param managedKeys A set of keys that are managed by the application. Keys
     *                    not in this
     *                    set found in the file will be preserved if not present in
     *                    the props.
     * @throws IOException If an I/O error occurs.
     */
    public static void savePropertiesPreservingFormat(Path file, Properties props, Set<String> managedKeys)
            throws IOException {
        List<String> lines = Files.exists(file) ? Files.readAllLines(file) : new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                newLines.add(line);
                continue;
            }

            int sepIdx = -1;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                    sepIdx = i;
                    break;
                }
            }

            if (sepIdx != -1) {
                String key = line.substring(0, sepIdx).trim();
                if (props.containsKey(key)) {
                    String val = props.getProperty(key);
                    newLines.add(key + "=" + escapePropertyValueForStorage(val));
                    processedKeys.add(key);
                } else {
                    if (managedKeys == null || !managedKeys.contains(key)) {
                        newLines.add(line);
                    }
                }
            } else {
                newLines.add(line);
            }
        }

        for (String key : props.stringPropertyNames()) {
            if (!processedKeys.contains(key)) {
                newLines.add(key + "=" + escapePropertyValueForStorage(props.getProperty(key)));
            }
        }
        Files.write(file, newLines);
    }

    private static String escapePropertyValueForStorage(String value) {
        String escaped = escapePropertyValue(value);
        if (value == null || value.isEmpty()) {
            return escaped;
        }
        int leadingSpaces = 0;
        while (leadingSpaces < value.length() && value.charAt(leadingSpaces) == ' ') {
            leadingSpaces++;
        }
        int trailingSpaces = 0;
        while (trailingSpaces < value.length()
                && value.charAt(value.length() - trailingSpaces - 1) == ' ') {
            trailingSpaces++;
        }
        StringBuilder result = new StringBuilder(escaped.length() + leadingSpaces + trailingSpaces);
        for (int i = 0; i < escaped.length(); i++) {
            if (i < leadingSpaces || i >= escaped.length() - trailingSpaces) {
                result.append("\\ ");
            } else {
                result.append(escaped.charAt(i));
            }
        }
        return result.toString();
    }

    /**
     * Escapes special characters (tab, newline, etc.) in a string for safe storage
     * in a properties file.
     *
     * @param value The value to escape.
     * @return The escaped string.
     */
    public static String escapePropertyValue(String value) {
        if (value == null)
            return "";
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (Character.isISOControl(current)) {
                        escaped.append(String.format(java.util.Locale.ROOT, "\\u%04X", (int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Makes control characters and literal backslashes visible in an editable
     * property value using Java-properties escapes.
     */
    public static String propertyValueForEditing(String value) {
        return escapePropertyValue(value);
    }

    public static String debugTraceSeparatorForEditing(String value) {
        return escapePropertyValue(value).replace(" ", "\\u0020");
    }

    public static String encodeWhitespaceForEditing(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        StringBuilder encoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case ' ' -> encoded.append("\\u0020");
                case '\t' -> encoded.append("\\t");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\f' -> encoded.append("\\f");
                default -> {
                    if (Character.isISOControl(current)) {
                        encoded.append(String.format(java.util.Locale.ROOT, "\\u%04X", (int) current));
                    } else {
                        encoded.append(current);
                    }
                }
            }
        }
        return encoded.toString();
    }

    /**
     * Decodes Java-properties escapes entered in an editable property value.
     */
    public static String propertyValueFromEditing(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\\' || i + 1 >= value.length()) {
                decoded.append(current);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case 't' -> decoded.append('\t');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 'f' -> decoded.append('\f');
                case '\\' -> decoded.append('\\');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        String hex = value.substring(i + 1, i + 5);
                        try {
                            decoded.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            decoded.append('\\').append(escaped);
                        }
                    } else {
                        decoded.append('\\').append(escaped);
                    }
                }
                default -> decoded.append('\\').append(escaped);
            }
        }
        return decoded.toString();
    }

    /**
     * Ensures that a configuration file exists. Creates parent directories and an
     * empty file if it's missing.
     *
     * @param file The path to the file.
     */
    public static void ensureConfigFileExists(Path file) {
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                Files.createFile(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Converts a {@link Color} object to a hexadecimal color string (e.g.,
     * "#ff0000").
     *
     * @param color The color to convert.
     * @return The hex string representation.
     */
    public static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * Resolves the absolute path to a profile file within a specific configuration
     * structure.
     *
     * @param confFolder The base configuration folder.
     * @param subFolder  The sub-folder (e.g., "node", "logging" — use {@link ModuleIds} constants).
     * @param fileName   The name of the file.
     * @return The resolved {@link Path}.
     * @deprecated Use resolveNodeProfilePath() or resolveLoggingProfilePath() for unified path resolution.
     */
    @Deprecated
    public static Path resolveProfilePath(String confFolder, String subFolder, String fileName) {
        return PathUtils.resolvePath(confFolder).resolve(subFolder).resolve(fileName);
    }

    /**
     * Resolves a node profile file using the unified PropertiesProfileLoader.
     * Path schema: ../conf/node/profiles/{profileName}.properties
     *
     * @param profileName Profile name without extension
     * @return The resolved Path to the profile file
     */
    public static Path resolveNodeProfilePath(String profileName) {
        return PropertiesProfileLoader.resolveProfileFile(
                ConfigPaths.RUNTIME_CONF_ROOT, ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, profileName);
    }

    /**
     * Resolves a logging profile file using the unified PropertiesProfileLoader.
     * Path schema: ../conf/node/logging/{profileName}.properties
     *
     * @param profileName Profile name without extension
     * @return The resolved Path to the logging profile file
     */
    public static Path resolveLoggingProfilePath(String profileName) {
        return PropertiesProfileLoader.resolveProfileFile(
                ConfigPaths.RUNTIME_CONF_ROOT, ModuleIds.NODE, ModuleIds.CATEGORY_LOGGING, profileName);
    }

    /**
     * Returns the path to the node profiles directory.
     * Path schema: ../conf/node/profiles/
     */
    public static Path getNodeProfilesDir() {
        return PropertiesProfileLoader.resolveProfileDir(
                ConfigPaths.RUNTIME_CONF_ROOT, ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES);
    }

    /**
     * Returns the path to the node logging directory.
     * Path schema: ../conf/node/logging/
     */
    public static Path getNodeLoggingDir() {
        return PropertiesProfileLoader.resolveProfileDir(
                ConfigPaths.RUNTIME_CONF_ROOT, ModuleIds.NODE, ModuleIds.CATEGORY_LOGGING);
    }

    /**
     * Loads the name of the applied profile from a metadata JSON file.
     *
     * @param profileJson Path to the profile.json metadata file.
     * @return The name of the applied profile, or null if not found.
     */
    public static String loadAppliedProfile(Path profileJson) {
        if (Files.exists(profileJson)) {
            try (BufferedReader reader = Files.newBufferedReader(profileJson, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has("appliedProfile")) {
                    return json.get("appliedProfile").getAsString();
                }
            } catch (Exception e) {
                // Ignore parse errors
            }
        }
        return null;
    }

    /**
     * Updates the metadata JSON file with the name of the currently applied
     * profile.
     *
     * @param profileJson Path to the profile.json metadata file.
     * @param profileName The name of the profile being applied.
     */
    public static void updateAppliedProfile(Path profileJson, String profileName) {
        try {
            JsonObject metadata;
            if (Files.exists(profileJson)) {
                try (BufferedReader reader = Files.newBufferedReader(profileJson, StandardCharsets.UTF_8)) {
                    metadata = JsonParser.parseReader(reader).getAsJsonObject();
                } catch (Exception e) {
                    metadata = new JsonObject();
                }
            } else {
                metadata = new JsonObject();
            }

            metadata.addProperty("appliedProfile", profileName);
            if (Files.notExists(profileJson.getParent())) {
                Files.createDirectories(profileJson.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(profileJson, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(metadata, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Renames a profile file. Checks if the destination file already exists before
     * moving.
     *
     * @param parent  The parent component for showing error dialogs.
     * @param oldFile Path to the existing profile file.
     * @param newFile Path to the new profile destination.
     * @param oldName Original profile name.
     * @param newName New profile name.
     * @return true if the rename was successful.
     * @throws IOException If a file movement error occurs.
     */
    public static boolean confirmAndRenameProfile(Component parent, Path oldFile, Path newFile, String oldName,
            String newName) throws IOException {
        if (Files.exists(newFile)) {
            JOptionPane.showMessageDialog(parent, "A profile with the name '" + newName + "' already exists.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (Files.exists(oldFile)) {
            Files.move(oldFile, newFile);
            return true;
        }
        return false;
    }

    /**
     * Configures a set of profile-management toolbar buttons as icon-only,
     * flat buttons in the node profile toolbar's icon-button pattern
     * (see {@code NodeToolbar#createIconButton}): a FontAwesome glyph at the
     * application's toolbar icon size that grows on hover
     * ({@link HoverScaleIcon}) without any layout shift, on a transparent,
     * unfilled button. The tooltip is the single source of information about
     * each action (set by the callers).
     *
     * @param newBtn             Button for creating a new profile.
     * @param saveBtn            Button for saving the current profile.
     * @param applyBtn           Button for applying the profile.
     * @param renameBtn          Button for renaming the profile.
     * @param deleteBtn          Button for deleting the profile.
     * @param reloadBtn          Button for reloading from disk.
     * @param refreshBtn         Button for refreshing the profile list.
     * @param resetToDefaultsBtn Button for resetting to application defaults.
     * @param copyBtn            Button for copying another profile's data.
     * @param cloneBtn           Button for cloning the current profile.
     */
    public static void configureProfileToolbar(
            JButton newBtn, JButton saveBtn, JButton applyBtn,
            JButton renameBtn, JButton deleteBtn, JButton reloadBtn, JButton refreshBtn,
            JButton resetToDefaultsBtn, JButton copyBtn, JButton cloneBtn) {
        float iconSize = GuiConstants.getToolBarIconSize();

        // Every action — including the "+" (New Profile) — is the same kind of
        // profile-toolbar button: a glyph at the toolbar icon size that grows on
        // hover by the shared {@link HoverScaleIcon#DEFAULT_SCALE}. The "+" renders
        // into the same non-square font-metric box as its FontAwesome siblings, so
        // the row's margins stay uniform and it grows in step with them.
        styleProfileActionButton(newBtn, ProfileActionIcon.NEW_PROFILE, iconSize);
        styleProfileActionButton(saveBtn, ProfileActionIcon.SAVE_PROFILE, iconSize);
        styleProfileActionButton(applyBtn, ProfileActionIcon.APPLY_PROFILE, iconSize);
        styleProfileActionButton(renameBtn, ProfileActionIcon.RENAME_PROFILE, iconSize);
        // delete stands out in the palette's contrast red (a destructive action)
        styleProfileActionButton(deleteBtn, ProfileActionIcon.DELETE_PROFILE, iconSize);
        styleProfileActionButton(reloadBtn, ProfileActionIcon.RELOAD_PROFILE, iconSize);
        styleProfileActionButton(refreshBtn, ProfileActionIcon.REFRESH_PROFILES, iconSize);
        styleProfileActionButton(resetToDefaultsBtn, ProfileActionIcon.RESET_PROFILE_TO_DEFAULTS, iconSize);
        styleProfileActionButton(copyBtn, ProfileActionIcon.COPY_PROFILE, iconSize);
        styleProfileActionButton(cloneBtn, ProfileActionIcon.CLONE_PROFILE, iconSize);
    }

    /**
     * Styles the New Profile action with the shared thin plus glyph
     * ({@link ProfileActionIcon#NEW_PROFILE}) and the standard profile-toolbar button
     * treatment — the same size and hover growth as the other profile actions, so
     * the "+" lines up and grows exactly like its siblings.
     */
    public static void styleNewProfileButton(JButton button) {
        styleProfileActionButton(button, ProfileActionIcon.NEW_PROFILE, GuiConstants.getToolBarIconSize());
    }

    /**
     * Styles a profile action with its semantic catalog icon and standard
     * profile-toolbar button treatment.
     */
    public static void styleProfileIconButton(JButton button, ProfileActionIcon icon, float iconSize) {
        styleProfileActionButton(button, icon, iconSize);
    }

    private static void styleProfileActionButton(JButton button, ProfileActionIcon icon, float iconSize) {
        if (button == null) {
            return;
        }
        icon.install(button, iconSize);
        styleProfileButtonSurface(button);
    }

    /**
     * Styles one profile-toolbar button in the node profile toolbar's flat
     * icon-button pattern (a glyph at the toolbar icon size, hover-grow,
     * no fill, a slim uniform border). This is the SINGLE method that defines
     * every profile toolbar button's look — the icon, the border, the fill AND
     * the cursor ({@link #PROFILE_TOOLBAR_CURSOR}: the standard arrow, never a
     * hand/pointer) — so the profile-action rows of all panels (node
     * configuration, the module logging profile panels, appearance, ...) keep
     * one identical appearance. A {@code null} button is skipped — the callers
     * pass only the buttons they own.
     */
    public static void styleProfileIconButton(JButton button, FontAwesome iconCode, Color color, float iconSize) {
        if (button == null) {
            return;
        }
        // P8 (delete-repro fix): route the profile-toolbar glyphs through the shared
        // IconFontCache — jiconfont re-inflates the ~165 KB FontAwesome TTF on every
        // buildIcon call, and this method styles EVERY profile-toolbar button across
        // all panels, so uncached builds thrash the IconFontSwing class lock.
        HoverScaleIcon.install(button,
                IconFontCache.buildIcon(iconCode, iconSize, color),
                IconFontCache.buildIcon(iconCode, iconSize * HoverScaleIcon.DEFAULT_SCALE, color));
        styleProfileButtonSurface(button);
    }

    private static void styleProfileButtonSurface(JButton button) {
        button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        button.setMargin(profileToolbarButtonMargin());
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setCursor(PROFILE_TOOLBAR_CURSOR);
    }

    /**
     * The custom-glyph variant of {@link #styleProfileIconButton(JButton, FontAwesome, Color, float)}:
     * the same flat profile-toolbar icon-button styling (hover-grow pair, slim uniform
     * border, unfilled surface, standard arrow cursor) around a caller-provided
     * {@link Icon} glyph instead of a FontAwesome code — e.g. the app's thin hand-drawn
     * plus ({@link PlusGlyphIcon}), which is not a FontAwesome glyph.
     *
     * @param button      the button to style (a {@code null} button is skipped)
     * @param normalGlyph the glyph used in the normal (non-hover) state
     * @param hoverGlyph  the glyph used in the rollover (hover) state (typically the
     *                    same glyph rendered at {@link HoverScaleIcon#DEFAULT_SCALE})
     */
    public static void styleProfileIconButton(JButton button, Icon normalGlyph, Icon hoverGlyph) {
        if (button == null) {
            return;
        }
        HoverScaleIcon.install(button, normalGlyph, hoverGlyph);
        styleProfileButtonSurface(button);
    }

    /**
     * The margin of EVERY profile-toolbar button: the standard L&F button
     * margin (FlatLaf's {@code Button.margin = 2,14,2,14}) — the same value a
     * plain {@link JButton} receives from its UI delegate. Pinned HERE, the
     * single source of truth, so buttons that override their own margin (the
     * appearance toolbar's {@link HelpButton} zeroes it in its constructor)
     * still end up with exactly the same geometry as a plain JButton in the
     * other toolbars — the ? glyph then sits at the same distance from the
     * profile selector in every profile row.
     */
    private static Insets profileToolbarButtonMargin() {
        Insets margin = UIManager.getInsets("Button.margin");
        return margin != null ? margin : new Insets(2, 14, 2, 14);
    }

    /**
     * Uniform gap between the profile-action toolbar buttons — the SAME
     * spacing the node profile toolbar (Start/Stop/Restart/...) uses, so the
     * profile-action rows of every panel (node configuration, the module
     * logging profile panels, ...) line up visually.
     */
    public static final int PROFILE_TOOLBAR_GAP = 5;

    /**
     * The standard top/left/bottom/right insets of a profile-action toolbar
     * row wrapped in its responsive scroll pane (the shared
     * {@link GuiConstants#TOOLBAR_INSETS}: top=5, left=10, bottom=2, right=5).
     * Every profile-action toolbar in the app (node configuration, module
     * logging profiles) must wrap its button row with these insets so the
     * rows have identical margins.
     */
    public static final Insets PROFILE_TOOLBAR_INSETS = GuiConstants.TOOLBAR_INSETS;

    /**
     * The cursor EVERY profile-action toolbar button uses: the STANDARD arrow
     * (never the hand/pointer). Defined ONCE here so the profile-action rows
     * of every panel (node configuration, the module logging profile panels,
     * appearance, ...) share one identical cursor — the "manage it in a single
     * place" anchor for the row's look, applied by
     * {@link #styleProfileIconButton}.
     */
    public static final Cursor PROFILE_TOOLBAR_CURSOR = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);

    /**
     * Maximum width (pixels) of the profile-action toolbar tooltips — the
     * mouse-over helper boxes of the New / Save / Apply / ... icon buttons in
     * the node configuration, logging, module logging and appearance rows. A
     * single, central value that is a <em>maximum</em>, not a fixed size: a
     * tooltip sizes itself to its text and is only capped (and wrapped onto
     * several lines) once the text would be wider than this maximum.
     */
    public static final int PROFILE_TOOLTIP_MAX_WIDTH = 360;

    /**
     * Builds the HTML tooltip shared by every button in the profile-action
     * toolbars (node configuration, logging, module logging and appearance).
     * The box sizes itself to the text: if the text's natural (widest line)
     * width fits within {@link #PROFILE_TOOLTIP_MAX_WIDTH} it is rendered at
     * that natural width, with no dead padding; if it would exceed the maximum
     * the body is pinned to the maximum so the long text wraps onto several
     * lines instead of stretching the box across the screen.
     *
     * @param text the tooltip body (plain text; use {@code <br>} for hard line breaks)
     * @return the ready-to-set HTML tooltip string, or {@code null} for a {@code null} body
     */
    public static String profileTooltip(String text) {
        if (text == null) {
            return null;
        }
        if (naturalTooltipWidth(text) <= PROFILE_TOOLTIP_MAX_WIDTH) {
            return "<html>" + text + "</html>";
        }
        return "<html><body width='" + PROFILE_TOOLTIP_MAX_WIDTH + "'>" + text + "</body></html>";
    }

    /**
     * The natural (unwrapped) preferred width of an HTML tooltip body, in the
     * default UI font: the width the widest {@code <br>} line would need to be
     * laid out on a single line. {@link #profileTooltip(String)} uses it to
     * decide whether the box renders at the text's natural width or is pinned
     * to {@link #PROFILE_TOOLTIP_MAX_WIDTH} (and wrapped).
     *
     * <p>Measured with a throwaway {@link JLabel} that shares the tooltip's HTML
     * rendering; it is created at button-construction time (a one-shot cost per
     * tooltip, not a per-frame one) and carries no border, so the width is the
     * pure HTML content width.
     */
    private static int naturalTooltipWidth(String text) {
        JLabel probe = new JLabel("<html>" + text + "</html>");
        probe.setBorder(BorderFactory.createEmptyBorder());
        return probe.getPreferredSize().width;
    }

    /**
     * Creates the profile-action toolbar ROW: a left-aligned button strip with
     * zero internal insets and the uniform {@link #PROFILE_TOOLBAR_GAP}
     * between buttons (the node/profile/configuration pattern).
     *
     * @return the row panel; add the icon buttons (styled with
     *         {@link #styleProfileIconButton}) to it in display order
     */
    public static JPanel createProfileToolbarRow() {
        net.miginfocom.swing.MigLayout layout =
                new net.miginfocom.swing.MigLayout("insets 0, gap " + PROFILE_TOOLBAR_GAP);
        JPanel row = new JPanel(layout);
        row.setBorder(BorderFactory.createEmptyBorder());
        return row;
    }

    /**
     * Wraps a profile-action toolbar row in the STANDARD responsive scroll
     * wrapper: the shared {@link #PROFILE_TOOLBAR_INSETS} margins, a
     * horizontal scrollbar only when the row outgrows the window, no vertical
     * scrollbar, and a fully transparent (borderless, non-opaque) pane — the
     * exact wrapper the node configuration panel and the module logging
     * profile panels use, so the toolbar rows have identical geometry
     * (button sizes, gaps and top/bottom/side margins) everywhere.
     *
     * @param row the toolbar row (see {@link #createProfileToolbarRow()})
     * @return the ready-to-add scroll pane
     */
    public static JScrollPane wrapProfileToolbarRow(JPanel row) {
        ResponsiveToolbarScrollPane scroll = new ResponsiveToolbarScrollPane(row, PROFILE_TOOLBAR_INSETS);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        return scroll;
    }

    /**
     * Creates a {@link JPanel} containing a visual legend for the configuration
     * status colors.
     *
     * @param parent The parent component used for help dialog positioning.
     * @return A panel showing color boxes and descriptions.
     */
    public static JPanel createLegendPanel(Component parent) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        panel.setBorder(new EmptyBorder(0, 0, 5, 0));

        panel.add(createLegendItem(GuiColors.getUnsaved(), "Unsaved values"));
        panel.add(createLegendItem(GuiColors.getSaved(), "Saved values"));
        panel.add(createLegendItem(GuiColors.getApplied(), "Applied values"));

        JButton helpBtn = new HelpButton();
        helpBtn.setToolTipText("Detailed Color Legend");
        helpBtn.addActionListener(e -> showColorLegendHelp(parent));
        panel.add(helpBtn);

        return panel;
    }

    /**
     * Helper to create an individual legend item consisting of a color box and
     * label.
     *
     * @param color The status color.
     * @param text  The description of what the color signifies.
     * @return A configured {@link JPanel}.
     */
    private static JPanel createLegendItem(Color color, String text) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JLabel colorBox = new JLabel("\u25A0");
        colorBox.setForeground(color);
        item.add(colorBox);
        item.add(new JLabel(text));
        return item;
    }

    /**
     * Shows a detailed information dialog explaining the color-coding scheme used
     * in
     * configuration panels.
     *
     * @param parent The component used as the dialog's owner.
     */
    public static void showColorLegendHelp(Component parent) {
        String msg = "<html><body style='width: 350px'>" +
                "<h3>Color Coding Legend</h3>" +
                "<p>The configuration values are color-coded to indicate their current status:</p>" +
                "<ul>" +
                "<li><b><font color='" + toHex(GuiColors.getUnsaved()) + "'>\u25A0 Unsaved Values:</font></b> " +
                "These values have been modified in the UI but have not yet been saved to the configuration file. " +
                "Properties with unsaved changes are marked with an asterisk (*).</li>" +
                "<li><b><font color='" + toHex(GuiColors.getSaved()) + "'>\u25A0 Saved Values:</font></b> " +
                "These values are saved in the currently loaded profile on disk, but they differ from the values " +
                "currently being used by the running node.</li>" +
                "<li><b><font color='" + toHex(GuiColors.getApplied()) + "'>\u25A0 Applied Values:</font></b> " +
                "These values match exactly what the node is currently using. Note that most changes require a restart to take effect.</li>"
                +
                "</ul>" +
                "</body></html>";
        JOptionPane.showMessageDialog(parent, msg, "Color Legend", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Updates the foreground color of a profile selection combo box based on
     * whether the
     * selected item is currently running, active, or modified.
     *
     * @param combo   The combo box to update.
     * @param running The name of the running profile.
     * @param active  The name of the active (applied) profile.
     */
    public static void updateProfileComboBoxColor(JComboBox<String> combo, String running, String active) {
        String selected = (String) combo.getSelectedItem();
        if (selected != null && selected.trim().equals(running)) {
            combo.setForeground(GuiColors.getApplied());
        } else if (selected != null && selected.trim().equals(active)) {
            combo.setForeground(GuiColors.getSaved());
        } else {
            combo.setForeground(UIManager.getColor("ComboBox.foreground"));
        }
    }

    /**
     * Resolves the path to the profile metadata JSON file for a specific
     * configuration category.
     *
     * @param confFolder Base configuration folder.
     * @param subFolder  Configuration sub-folder (e.g., "node").
     * @return The path to profile.json.
     */
    public static Path getProfileMetadataPath(String confFolder, String subFolder) {
        return PathUtils.resolvePath(confFolder).resolve(subFolder).resolve("profile.json");
    }

    /**
     * Scans a directory for properties-based configuration profiles.
     *
     * @param folder          The directory to scan.
     * @param excludeFileName A filename to exclude (usually the base default file).
     * @return A list of profile names found (filename minus extension).
     */
    public static List<String> fetchProfileNames(Path folder, String excludeFileName) {
        if (Files.notExists(folder))
            return new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".properties") && !name.equals(excludeFileName))
                    .map(name -> name.substring(0, name.length() - 11))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Reads the logging configuration according to the two-layer model:
     * <pre>
     *   L0: hardcoded internal defaults ({@link LoggerProfile#applyInternalDefaults()}).
     *   L2: {confFolder}/node/logging/{profileName}.properties, falling back to
     *       the shared "logging.properties" — its active lines override L0.
     * </pre>
     * Path schema: {confFolder}/node/logging/{profileName}.properties
     *
     * @param confFolder  the base configuration root (e.g. "./conf"), never null
     * @param profileName the logging profile name to load
     * @return the effective LoggerProfile (L0 + the active lines of L2)
     */
    public static LoggerProfile loadEffectiveLoggerProfile(String confFolder, String profileName) {
        LoggerProfile effective = new LoggerProfile(profileName);
        effective.applyInternalDefaults();

        // L2: the profile's own file, falling back to the shared "logging" file.
        Path pathToLoad = PropertiesProfileLoader.resolveProfileFile(
                confFolder, ModuleIds.NODE, ModuleIds.CATEGORY_LOGGING, profileName);
        if (!Files.exists(pathToLoad) && !Signum.LOGGING_PROPERTIES_NAME.equals(profileName)) {
            Path fallbackPath = PropertiesProfileLoader.resolveProfileFile(
                    confFolder, ModuleIds.NODE, ModuleIds.CATEGORY_LOGGING, Signum.LOGGING_PROPERTIES_NAME);
            if (Files.exists(fallbackPath)) {
                pathToLoad = fallbackPath;
            }
        }
        loadLoggingLayer(effective, pathToLoad);
        return effective;
    }

    /**
     * Loads the named logging layer: {confFolder}/node/logging/{profileName}.properties,
     * when it exists.
     */
    private static void loadLoggingLayer(LoggerProfile target, String confFolder, String profileName) {
        loadLoggingLayer(target, PropertiesProfileLoader.resolveProfileFile(confFolder,
                ModuleIds.NODE, ModuleIds.CATEGORY_LOGGING, profileName));
    }

    /**
     * Loads one logging layer from an already-resolved path, tolerating a
     * missing or unreadable file (the layers below stay effective).
     */
    private static void loadLoggingLayer(LoggerProfile target, Path path) {
        if (!Files.exists(path)) {
            return; // layer absent
        }
        try (FileInputStream is = new FileInputStream(path.toFile())) {
            Properties fileProps = new Properties();
            fileProps.load(is);
            target.getProperties().putAll(fileProps);
        } catch (IOException e) {
            // Logger initialization has not happened yet, using System.err
            System.err.println("Failed to load logger properties from " + path + ": " + e.getMessage());
        }
    }

    /**
     * Reads the node configuration according to the priority order.
     * Path schema: ../conf/node/profiles/{profileName}.properties
     */
    public static NodeProfile loadEffectiveNodeProfile(String confFolder, String profileName) {
        NodeProfile effective = new NodeProfile(profileName);

        // 1. Search based on the active name using unified loader
        Path specificPath = PropertiesProfileLoader.resolveProfileFile(
                ConfigPaths.RUNTIME_CONF_ROOT, ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, profileName);
        if (Files.exists(specificPath)) {
            try (FileInputStream is = new FileInputStream(specificPath.toFile())) {
                effective.getProperties().load(is);
            } catch (IOException e) {
                System.err.println("Failed to load node profile: " + e.getMessage());
            }
        } else {
            // 2. Fallback to "node" profile
            if (!Signum.PROPERTIES_NAME.equals(profileName)) {
                Path fallbackPath = PropertiesProfileLoader.resolveProfileFile(
                        ConfigPaths.RUNTIME_CONF_ROOT, ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, Signum.PROPERTIES_NAME);
                if (Files.exists(fallbackPath)) {
                    try (FileInputStream is = new FileInputStream(fallbackPath.toFile())) {
                        effective.getProperties().load(is);
                    } catch (IOException e) {
                        System.err.println("Failed to load fallback node profile: " + e.getMessage());
                    }
                }
            }
        }
        return effective;
    }

    /**
     * Reads a Look and Feel profile from the gui-settings.json file.
     */
    public static AppearanceProfile loadLookAndFeelProfile(Path settingsPath, String profileName) {
        AppearanceProfile profile = new AppearanceProfile(profileName);
        if (Files.notExists(settingsPath))
            return profile;

        try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
            JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
            if (settings.has("lookAndFeelProfiles")) {
                JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");
                if (profiles.has(profileName)) {
                    JsonObject data = profiles.getAsJsonObject(profileName);

                    if (data.has("theme")) {
                        profile.setThemeClassName(data.get("theme").getAsString());
                    }
                    if (data.has("font")) {
                        profile.setGlobalFont(parseJsonFont(data.getAsJsonObject("font")));
                    }
                    if (data.has("consoleFont")) {
                        profile.setConsoleFont(parseJsonFont(data.getAsJsonObject("consoleFont")));
                    }
                    if (data.has("colorOverrides")) {
                        Map<String, Color> overrides = new java.util.HashMap<>();
                        JsonObject colors = data.getAsJsonObject("colorOverrides");
                        for (Map.Entry<String, JsonElement> entry : colors.entrySet()) {
                            overrides.put(entry.getKey(), Color.decode(entry.getValue().getAsString()));
                        }
                        profile.setColorOverrides(overrides);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load LAF profile: " + e.getMessage());
        }
        return profile;
    }

    private static Font parseJsonFont(JsonObject obj) {
        if (obj == null)
            return null;
        try {
            return new Font(
                    obj.get("family").getAsString(),
                    obj.get("style").getAsInt(),
                    obj.get("size").getAsInt());
        } catch (Exception e) {
            return null;
        }
    }
}
