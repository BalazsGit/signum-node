package application.module.node.gui.wizard;

import application.module.node.gui.wizard.WizardStep;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.function.Consumer;

/**
 * Modal setup-wizard dialog (plan §1.5): CardLayout step panels + Back / Next(Finish) / Cancel.
 * <p>
 * Navigation state is owned by {@link NodeSetupWizardController}; this class only renders
 * the current step and delegates validation to the controller. The wizard's <b>title bar
 * shows the chosen profile name and the network live</b> (the name is decided in the
 * first step and the suggested one follows the later network/engine/port selections). Before leaving a step
 * the step's {@link WizardStep#confirmExit} hook is consulted (e.g. the SQLite database
 * step confirms the database that will be created). On successful finish the
 * profile is created via {@link WizardFinish} and the {@code onFinished} callback
 * (e.g. {@code NodePanel::addProfileTab}) is invoked; on cancel only the
 * session-created node profile is removed again (the database profiles created in
 * the session remain — v2, AD-06).
 * </p>
 */
public class NodeSetupWizardDialog extends JDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeSetupWizardDialog.class);

    private final NodeSetupWizardController controller;
    /** The step wrappers (one per step id) — swapped into {@link #stepsPanel} on show. */
    private final java.util.Map<String, JComponent> stepWrappers = new java.util.LinkedHashMap<>();
    private final JPanel stepsPanel = new JPanel(new java.awt.BorderLayout());
    private final JLabel stepIndicator = new JLabel();
    private final JLabel headerIconLabel = new JLabel();
    private final JButton backButton = new JButton("Back");
    private final JButton nextButton = new JButton("Next");
    private final JButton cancelButton = new JButton("Cancel");
    private final Consumer<String> onFinished;

    /**
     * @param parent     parent frame (may be null)
     * @param onFinished called with the created profile name after a successful finish (may be null)
     */
    public NodeSetupWizardDialog(Frame parent, Consumer<String> onFinished) {
        super(parent, "Create Node Profile", true);
        this.onFinished = onFinished;
        this.controller = new NodeSetupWizardController();
        // Live context changes (e.g. the profile name, the engine radio on the
        // selection step) alter which steps are visible — refresh the "Step X of Y"
        // indicator and the title bar (the chosen profile name) without disturbing
        // focus (no card switch, no focus request).
        this.controller.addChangeListener(ctx -> updateIndicator());
        initialize();
        showStep();
    }

    private void initialize() {
        setLayout(new BorderLayout(0, 6));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(780, 640);
        setLocationRelativeTo(getParent());

        stepsPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        for (WizardStep step : controller.getSteps()) {
            JPanel wrapper = new JPanel(new java.awt.BorderLayout());
            wrapper.add(step.getPanel(), java.awt.BorderLayout.CENTER);
            stepWrappers.put(step.getId(), wrapper);
        }
        // The step content is variable-height (the node-configuration step's
        // advanced settings embed the full profile editor) — a vertical
        // scrollbar keeps every step fully reachable inside the dialog. The
        // horizontal scrollbar only appears when the dialog is shrunk below
        // the content width (the text then stays readable, never clipped).
        // Only the CURRENT step's wrapper lives in stepsPanel (swapped in
        // showStep), so the preferred size follows the visible step and the
        // vertical scrollbar is hidden whenever the step's content fits.
        JScrollPane stepsScroll = new JScrollPane(stepsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(stepsScroll, BorderLayout.CENTER);

        // "Step x/y — Title" is the wizard's heading: same proportional 1.2×
        // bold keyword style as the in-step titles (WizardStep.KEYWORD_FONT_SCALE).
        stepIndicator.setFont(GuiFontManager.getBoldScaledDefaultFont(WizardStep.KEYWORD_FONT_SCALE));
        JPanel indicatorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        // Header icon in the dialog's top-left corner: topic-relevant per step
        // (e.g. the database icon on the database steps) — see
        // WizardStep#getHeaderIcon; same pattern as the Save Changes dialog's
        // header icon.
        indicatorPanel.add(headerIconLabel);
        indicatorPanel.add(stepIndicator);
        add(indicatorPanel, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        backButton.setFocusable(false);
        nextButton.setFocusable(false);
        cancelButton.setFocusable(false);
        backButton.addActionListener(e -> {
            controller.back();
            showStep();
        });
        nextButton.addActionListener(e -> handleNext());
        cancelButton.addActionListener(e -> handleCancel());
        buttons.add(backButton);
        buttons.add(nextButton);
        buttons.add(cancelButton);
        add(buttons, BorderLayout.SOUTH);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                handleCancel();
            }
        });
    }

    /** Shows the controller's current step and updates indicator/button states. */
    private void showStep() {
        WizardStep step = controller.currentStep();
        // Swap in the current step's wrapper (the panel's preferred size then
        // follows the VISIBLE step — the vertical scrollbar only appears when
        // this step's content really does not fit).
        stepsPanel.removeAll();
        JComponent wrapper = stepWrappers.get(step.getId());
        if (wrapper != null) {
            stepsPanel.add(wrapper, java.awt.BorderLayout.CENTER);
        }
        stepsPanel.revalidate();
        stepsPanel.repaint();
        FontAwesome headerIcon = step.getHeaderIcon() != null
                ? step.getHeaderIcon() : FontAwesome.INFO_CIRCLE;
        headerIconLabel.setIcon(IconFontSwing.buildIcon(headerIcon, GuiConstants.ICON_SIZE_DIALOG,
                GuiColors.getButtonIcon()));
        backButton.setEnabled(!controller.isFirstStep());
        updateIndicator();
        nextButton.requestFocusInWindow();
    }

    /**
     * Refreshes the "Step X of Y — Title" indicator, the Next/Finish
     * label and the <b>title bar (the chosen profile name + the network)</b>
     * from the controller's <b>effective</b> (context-dependent) step sequence, so
     * auto-skipped steps (e.g. the server-only DB steps for SQLite) are never
     * counted. The network (mainnet / testnet — decided in the first step) is
     * part of the window title, next to the profile name. Also called on live context changes
     * (name typing, engine selection) — it deliberately does NOT request focus,
     * so the user's interaction with the step's controls is not interrupted.
     */
    private void updateIndicator() {
        WizardStep step = controller.currentStep();
        stepIndicator.setText("Step " + controller.getCurrentVisibleIndex() + " of "
                + controller.getVisibleStepCount() + " — " + step.getTitle());
        nextButton.setText(controller.isLastStep() ? "Finish" : "Next");
        // The network (decided in the first step) lives in the WINDOW header
        // next to the chosen profile name — the "Step X of Y" row stays
        // network-free.
        String name = controller.getContext().getName();
        String network = controller.getContext().getNetwork().getDisplayName();
        setTitle(name == null || name.isBlank()
                ? "Create Node Profile (" + network + ")"
                : "Create Node Profile — " + name + " (" + network + ")");
    }

    private void handleNext() {
        // On the LAST visible step the button reads "Finish" and completes the
        // wizard. Only then is the profile created — every other click validates
        // and advances, so the final (Summary) step is actually shown.
        if (controller.isLastStep()) {
            handleFinish();
            return;
        }
        // The step may ask for an explicit confirmation before leaving (the
        // SQLite database step confirms the database that will be created).
        if (!controller.currentStep().confirmExit(this, controller.getContext())) {
            return;
        }
        String error = controller.next();
        if (error != null) {
            JOptionPane.showMessageDialog(this, error, "Please fix the highlighted input",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        showStep();
    }

    private void handleFinish() {
        // Same exit confirmation on the last step (e.g. the advanced editor's
        // unsaved changes while finishing from the node-configuration step).
        if (!controller.currentStep().confirmExit(this, controller.getContext())) {
            return;
        }
        String error = controller.finish();
        if (error != null) {
            JOptionPane.showMessageDialog(this, error, "Please fix the highlighted input",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            String name = WizardFinish.createProfile(controller.getContext());
            LOGGER.info("Setup wizard finished: profile '{}' created", name);
            dispose();
            if (onFinished != null) {
                onFinished.accept(name);
            }
        } catch (Exception e) {
            LOGGER.error("Setup wizard finish failed", e);
            JOptionPane.showMessageDialog(this,
                    "Failed to create the profile:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Closes the wizard without creating the profile: the profile file is
     * created only on Finish (while the wizard runs, the configuration lives in
     * the virtual in-memory session profile), so a cancel leaves nothing to
     * clean up. The database profiles created during the session are left in
     * place as well (the user manages them from the Database module).
     */
    private void handleCancel() {
        dispose();
    }

    /** The wizard's state machine (same-package access for the GUI regression tests). */
    NodeSetupWizardController getController() {
        return controller;
    }
}