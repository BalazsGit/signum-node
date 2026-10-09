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
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.function.Consumer;

/**
 * Modal setup-wizard dialog (plan §1.5): CardLayout step panels + Back / Next(Finish) / Cancel.
 * <p>
 * Navigation state is owned by {@link NodeSetupWizardController}; this class only renders
 * the current step and delegates validation to the controller. On successful finish the
 * profile is created via {@link WizardFinish} and the {@code onFinished} callback
 * (e.g. {@code NodePanel::addProfileTab}) is invoked.
 * </p>
 */
public class NodeSetupWizardDialog extends JDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeSetupWizardDialog.class);

    private final NodeSetupWizardController controller;
    private final CardLayout cards = new CardLayout();
    private final JPanel stepsPanel = new JPanel();
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
        // Live context changes (e.g. the engine radio on the selection step)
        // alter which steps are visible — refresh the "Step X of Y" indicator
        // without disturbing focus (no card switch, no focus request).
        this.controller.addChangeListener(ctx -> updateIndicator());
        initialize();
        showStep();
    }

    private void initialize() {
        setLayout(new BorderLayout(0, 10));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(780, 640);
        setLocationRelativeTo(getParent());

        stepsPanel.setLayout(cards);
        stepsPanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 0, 16));
        for (WizardStep step : controller.getSteps()) {
            JPanel wrapper = new JPanel(new java.awt.BorderLayout());
            wrapper.add(step.getPanel(), java.awt.BorderLayout.CENTER);
            stepsPanel.add(wrapper, step.getId());
        }
        add(stepsPanel, BorderLayout.CENTER);

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
        cancelButton.addActionListener(e -> dispose());
        buttons.add(backButton);
        buttons.add(nextButton);
        buttons.add(cancelButton);
        add(buttons, BorderLayout.SOUTH);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                dispose();
            }
        });
    }

    /** Shows the controller's current step and updates indicator/button states. */
    private void showStep() {
        WizardStep step = controller.currentStep();
        cards.show(stepsPanel, step.getId());
        FontAwesome headerIcon = step.getHeaderIcon() != null
                ? step.getHeaderIcon() : FontAwesome.INFO_CIRCLE;
        headerIconLabel.setIcon(IconFontSwing.buildIcon(headerIcon, GuiConstants.ICON_SIZE_DIALOG,
                GuiColors.getButtonIcon()));
        backButton.setEnabled(!controller.isFirstStep());
        updateIndicator();
        nextButton.requestFocusInWindow();
    }

    /**
     * Refreshes the "Step X of Y" indicator and the Next/Finish label from the
     * controller's <b>effective</b> (context-dependent) step sequence, so
     * auto-skipped steps (e.g. the server-only DB steps for SQLite) are never
     * counted. Also called on live context changes (engine selection) — it
     * deliberately does NOT request focus, so the user's interaction with the
     * step's controls is not interrupted.
     */
    private void updateIndicator() {
        WizardStep step = controller.currentStep();
        stepIndicator.setText("Step " + controller.getCurrentVisibleIndex() + " of "
                + controller.getVisibleStepCount() + " — " + step.getTitle());
        nextButton.setText(controller.isLastStep() ? "Finish" : "Next");
    }

    private void handleNext() {
        // On the LAST visible step the button reads "Finish" and completes the
        // wizard. Only then is the profile created — every other click validates
        // and advances, so the final (Summary) step is actually shown.
        if (controller.isLastStep()) {
            handleFinish();
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
}