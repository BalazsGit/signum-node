package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.utils.gui.GuiFontManager;
import jiconfont.icons.font_awesome.FontAwesome;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Wizard step 1 — database engine selection (SSOT: {@link DatabaseEngine}).
 * <p>
 * Traditional radio-button layout (one plain entry per engine). Selecting an
 * engine updates the "Engine details" panel below with the engine's official
 * logo and engine-specific information (description, required setup, typical
 * usage — all sourced from the {@link DatabaseEngine} SSOT, not static UI text).
 * </p>
 */
public class DatabaseSelectionStep implements WizardStep {

    private static final int DETAILS_LOGO_SIZE = 48;

    private final JPanel panel = new JPanel();
    private final ButtonGroup group = new ButtonGroup();
    private final Map<DatabaseEngine, JRadioButton> buttons = new LinkedHashMap<>();

    private final JLabel logoLabel = new JLabel();
    private final JLabel engineNameLabel = new JLabel();
    // The details section keywords (headings, KEYWORD_FONT_SCALE bigger) and the
    // section body texts (default font, HTML-wrapped) as separate labels — Swing's
    // HTML font sizes cannot express a proportional 120% step on one combined label.
    private final JLabel descriptionKey = new JLabel("Description");
    private final JLabel setupKey = new JLabel("Setup required");
    private final JLabel usageKey = new JLabel("Best for");
    private final JLabel descriptionText = new JLabel();
    private final JLabel setupText = new JLabel();
    private final JLabel usageText = new JLabel();
    private DatabaseEngine shownEngine = null;

    /** Live navigation context (bound by the controller; null in standalone use). */
    private WizardContext boundContext = null;
    private final List<Consumer<WizardContext>> changeListeners = new ArrayList<>();

    public DatabaseSelectionStep() {
        // BorderLayout (NOT BoxLayout) so the "Engine details" box below the radios
        // always spans the FULL panel width: its content (logo/text) changes width per
        // engine, and a BoxLayout would re-center the width-varying box on every
        // selection — the radio section visually "wandered" left/right. Here the radio
        // group stays pinned top-left and only the details CONTENT changes.
        panel.setLayout(new BorderLayout(0, 10));
        panel.setOpaque(false);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);

        JLabel title = new JLabel("1. Select the database engine for this node");
        title.setFont(GuiFontManager.getBoldScaledDefaultFont(KEYWORD_FONT_SCALE));
        title.setAlignmentX(0f);
        top.add(title);

        for (DatabaseEngine engine : DatabaseEngine.values()) {
            JRadioButton button = new JRadioButton(engine.getDisplayName());
            // BoxLayout alignment: the Swing default (alignmentX = 0.5f) would
            // CENTER each button in the panel, so the radio group "wanders"
            // left/right as the selected (wider) entry changes. Pinning every
            // radio to the left edge keeps the group in a fixed position.
            button.setAlignmentX(0f);
            if (engine == DatabaseEngine.SQLITE) {
                button.setSelected(true);
            }
            button.addActionListener(e -> select(engine));
            group.add(button);
            buttons.put(engine, button);
            top.add(button);
        }
        panel.add(top, BorderLayout.NORTH);

        JPanel details = new JPanel(new BorderLayout(10, 6));
        details.setOpaque(false);
        details.setBorder(BorderFactory.createTitledBorder("Engine details"));

        // Header: the (bigger) official engine logo with the engine name BELOW it
        // (stacked vertically — the name must not sit on the icon's row).
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        GuiFontManager.applyDefaultFont(logoLabel);
        engineNameLabel.setFont(GuiFontManager.getBoldScaledDefaultFont(KEYWORD_FONT_SCALE));
        logoLabel.setAlignmentX(0f);
        engineNameLabel.setAlignmentX(0f);
        header.add(logoLabel);
        header.add(javax.swing.Box.createVerticalStrut(4));
        header.add(engineNameLabel);
        details.add(header, BorderLayout.NORTH);

        // Body: one section per property (keyword heading + wrapped text).
        JPanel detailsBody = new JPanel();
        detailsBody.setLayout(new BoxLayout(detailsBody, BoxLayout.Y_AXIS));
        detailsBody.setOpaque(false);
        for (JLabel key : new JLabel[] { descriptionKey, setupKey, usageKey }) {
            key.setFont(GuiFontManager.getBoldScaledDefaultFont(KEYWORD_FONT_SCALE));
            key.setAlignmentX(0f);
        }
        for (JLabel body : new JLabel[] { descriptionText, setupText, usageText }) {
            GuiFontManager.applyDefaultFont(body);
            body.setVerticalAlignment(SwingConstants.TOP);
            body.setAlignmentX(0f);
        }
        detailsBody.add(descriptionKey);
        detailsBody.add(descriptionText);
        detailsBody.add(javax.swing.Box.createVerticalStrut(8));
        detailsBody.add(setupKey);
        detailsBody.add(setupText);
        detailsBody.add(javax.swing.Box.createVerticalStrut(8));
        detailsBody.add(usageKey);
        detailsBody.add(usageText);
        details.add(detailsBody, BorderLayout.CENTER);
        panel.add(details, BorderLayout.CENTER); // full width, stable on every selection

        select(DatabaseEngine.SQLITE); // initial details for the preselected engine
    }

    /** Shows the details of the given engine and keeps the radio group in sync. */
    private void select(DatabaseEngine engine) {
        buttons.forEach((engineKey, b) -> b.setSelected(b == buttons.get(engine)));
        if (engine == shownEngine) {
            return;
        }
        shownEngine = engine;
        logoLabel.setIcon(engine.getLogoIcon(DETAILS_LOGO_SIZE));
        engineNameLabel.setText(engine.getDisplayName());
        updateDetailsTexts(engine);
        panel.revalidate();
        // Live: the engine drives which steps the wizard walks through (SQLite
        // skips the installation and connection steps). Mirror the choice into
        // the shared context immediately and let the controller refresh the
        // context-dependent state (the "Step X of Y" indicator) dynamically.
        if (boundContext != null) {
            boundContext.setEngine(engine);
            for (Consumer<WizardContext> listener : changeListeners) {
                listener.accept(boundContext);
            }
        }
    }

    /**
     * Shows the engine-specific details from the {@link DatabaseEngine} SSOT: the
     * section keywords render at the keyword heading size, the body texts at the
     * default font (HTML-wrapped so long descriptions wrap in the panel).
     */
    private void updateDetailsTexts(DatabaseEngine engine) {
        descriptionText.setText("<html>" + engine.getDescription() + "</html>");
        setupText.setText("<html>" + engine.getSetupRequirements() + "</html>");
        usageText.setText("<html>" + engine.getUsageNotes() + "</html>");
    }

    /** @return the currently selected engine (never null; default SQLite). */
    public DatabaseEngine getSelectedEngine() {
        for (Map.Entry<DatabaseEngine, JRadioButton> e : buttons.entrySet()) {
            if (e.getValue().isSelected()) {
                return e.getKey();
            }
        }
        return DatabaseEngine.SQLITE;
    }

    @Override
    public String getId() {
        return "database-selection";
    }

    @Override
    public String getTitle() {
        return "Database";
    }

    @Override
    public FontAwesome getHeaderIcon() {
        return FontAwesome.DATABASE;
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    public String validate(WizardContext context) {
        return null; // engine is always chosen (radio group, SQLite preselected)
    }

    @Override
    public void bindContext(WizardContext context) {
        this.boundContext = context;
        // Mirror the current (preselected) engine immediately so the wizard's
        // context-dependent state (visible step count, skip logic) is correct
        // from the very first indicator paint — before this step's onExit runs.
        context.setEngine(getSelectedEngine());
    }

    @Override
    public void addChangeListener(Consumer<WizardContext> listener) {
        changeListeners.add(listener);
    }

    @Override
    public void onExit(WizardContext context) {
        context.setEngine(getSelectedEngine());
    }
}