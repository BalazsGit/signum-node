package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.wizard.ServerDatabaseProfileWizard;
import application.module.node.gui.wizard.SqliteDatabaseProfileWizard;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.module.node.gui.wizard.WizardTexts;
import application.utils.gui.GuiFontManager;
import jiconfont.icons.font_awesome.FontAwesome;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Wizard step 2 — database engine selection (SSOT: {@link DatabaseEngine}).
 * <p>
 * Traditional radio-button layout (one plain entry per engine). Selecting an
 * engine updates the "Engine details" panel below with the engine's official
 * logo and engine-specific information (description, required setup, typical
 * usage — all sourced from the {@link DatabaseEngine} SSOT, not static UI text).
 * </p>
 */
public class DatabaseSelectionStep implements WizardStep {

    private static final int DETAILS_LOGO_SIZE = 48;

    /**
     * The step's root container: it keeps the content
     * <b>top-anchored</b> (the same structure as the other steps). The dialog
     * stretches every step panel to the full available height — without this
     * the extra space would be handed to the stretchable text panes, opening
     * visible blank gaps between the rows.
     */
    private final JPanel panel = new JPanel(new BorderLayout());
    private final ButtonGroup group = new ButtonGroup();
    private final Map<DatabaseEngine, JRadioButton> buttons = new LinkedHashMap<>();

    private final JLabel logoLabel = new JLabel();
    private final javax.swing.JEditorPane engineNameLabel = WizardTexts.heading("");
    // The details section keywords (headings, KEYWORD_FONT_SCALE bigger) and the
    // section body texts (default font, HTML-wrapped) as separate selectable
    // text panes — Swing's HTML font sizes cannot express a proportional 120%
    // step on one combined label, and every display text of the wizard must
    // stay selectable.
    private final javax.swing.JEditorPane descriptionKey = WizardTexts.heading("Description");
    private final javax.swing.JEditorPane setupKey = WizardTexts.heading("Setup required");
    private final javax.swing.JEditorPane usageKey = WizardTexts.heading("Best for");
    private final javax.swing.JEditorPane descriptionText = WizardTexts.info("");
    private final javax.swing.JEditorPane setupText = WizardTexts.info("");
    private final javax.swing.JEditorPane usageText = WizardTexts.info("");
    private DatabaseEngine shownEngine = null;

    /** Live navigation context (bound by the controller; null in standalone use). */
    private WizardContext boundContext = null;
    private final List<Consumer<WizardContext>> changeListeners = new ArrayList<>();

    public DatabaseSelectionStep() {
        panel.setOpaque(false);

        // The step content: the same 4 px border as every other step, so the
        // main title lands at exactly the same spot as the previous step's.
        // BorderLayout (NOT BoxLayout) so the "Engine details" box below the
        // radios always spans the FULL content width: its content (logo/text)
        // changes width per engine, and a BoxLayout would re-center the
        // width-varying box on every selection — the radio section visually
        // "wandered" left/right. Here the radio group stays pinned top-left
        // and only the details CONTENT changes.
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);

        javax.swing.JEditorPane title = WizardTexts.heading("Select the database engine for this node");
        title.setAlignmentX(0f);
        top.add(title);

        // A normal empty line between the title and the engine selection.
        javax.swing.JEditorPane blankLine = WizardTexts.info("");
        blankLine.setAlignmentX(0f);
        top.add(blankLine);

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
        content.add(top, BorderLayout.NORTH);

        JPanel details = new JPanel(new BorderLayout(8, 4));
        details.setOpaque(false);
        details.setBorder(BorderFactory.createTitledBorder("Engine details"));

        // Header: the (bigger) official engine logo with the engine name BELOW it
        // (stacked vertically — the name must not sit on the icon's row).
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        GuiFontManager.applyDefaultFont(logoLabel);
        logoLabel.setAlignmentX(0f);
        engineNameLabel.setAlignmentX(0f);
        header.add(logoLabel);
        header.add(javax.swing.Box.createVerticalStrut(4));
        header.add(engineNameLabel);
        // A normal empty line below the engine name (before the section keywords).
        javax.swing.JEditorPane nameGap = WizardTexts.info("");
        nameGap.setAlignmentX(0f);
        header.add(nameGap);
        details.add(header, BorderLayout.NORTH);

        // Body: one section per property (keyword heading + wrapped text).
        JPanel detailsBody = new JPanel();
        detailsBody.setLayout(new BoxLayout(detailsBody, BoxLayout.Y_AXIS));
        detailsBody.setOpaque(false);
        for (javax.swing.JEditorPane key : new javax.swing.JEditorPane[] { descriptionKey, setupKey, usageKey }) {
            key.setAlignmentX(0f);
        }
        for (javax.swing.JEditorPane body : new javax.swing.JEditorPane[] { descriptionText, setupText, usageText }) {
            body.setAlignmentX(0f);
        }
        detailsBody.add(descriptionKey);
        detailsBody.add(descriptionText);
        detailsBody.add(javax.swing.Box.createVerticalStrut(4));
        detailsBody.add(setupKey);
        detailsBody.add(setupText);
        detailsBody.add(javax.swing.Box.createVerticalStrut(4));
        detailsBody.add(usageKey);
        detailsBody.add(usageText);
        details.add(detailsBody, BorderLayout.CENTER);
        // The "Engine details" box spans the full content width (stable across
        // engine selections). The database source (a dedicated URL vs. a linked
        // database profile) is decided on the NEXT step — the database-configuration
        // step's "Linked Database Profile" checkbox — so it is not offered here.
        content.add(details, BorderLayout.CENTER);

        // Top-anchored: the dialog stretches the step's root panel to the full
        // available height — NORTH keeps the content at its preferred height
        // and leaves the extra space below (see the panel field's javadoc).
        panel.add(content, BorderLayout.NORTH);

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
        engineNameLabel.setText(WizardTexts.wrap(WizardTexts.escape(engine.getDisplayName())));
        updateDetailsTexts(engine);
        panel.revalidate();
        // Live: the engine drives which steps the wizard walks through (SQLite
        // skips the installation step). Mirror the choice into the shared context
        // immediately and let the controller refresh the context-dependent state
        // (the "Step X of Y" indicator) dynamically.
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
        descriptionText.setText(WizardTexts.wrap(engine.getDescription()));
        setupText.setText(WizardTexts.wrap(engine.getSetupRequirements()));
        usageText.setText(WizardTexts.wrap(engine.getUsageNotes()));
    }

    /**
     * Launches the engine's database-profile wizard and returns the created profile
     * name (or {@code null} when the user cancelled). The {@code "+"} buttons of the
     * database-configuration surfaces implement this contract (the install wizard's
     * step and the node configuration's DB.Url row share the behavior, and the tests
     * substitute a headless implementation).
     */
    @FunctionalInterface
    public interface DatabaseProfileWizardLauncher {
        /**
         * @param engine                 the engine of the profile to create
         * @param suggestedProfileName   the profile name to prefill in the wizard,
         *                               or {@code null} for no prefill
         * @return the created profile name, or {@code null} when cancelled
         */
        String create(DatabaseEngine engine, String suggestedProfileName);
    }

    /**
     * The default profile-wizard launcher (SSOT): the SQLite database-profile wizard
     * (G1) for SQLite, the server database-profile wizard (G2) for the server engines,
     * prefilled with the suggested profile name ({@code null}/blank: no prefill).
     * Public so the node configuration's DB.Url row (a different package) reuses the
     * exact same engine → wizard mapping.
     */
    public static String defaultProfileWizardLauncher(DatabaseEngine engine, String suggestedProfileName) {
        if (engine == DatabaseEngine.SQLITE) {
            return SqliteDatabaseProfileWizard.show(null, suggestedProfileName);
        }
        return ServerDatabaseProfileWizard.show(null, engine, suggestedProfileName);
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

    @Override
    public void onEnter(WizardContext context) {
        // The engine is re-selectable on back — restore the details for it.
        DatabaseEngine engine = context.getEngine() == null ? DatabaseEngine.SQLITE : context.getEngine();
        if (engine != shownEngine) {
            select(engine);
        }
    }
}