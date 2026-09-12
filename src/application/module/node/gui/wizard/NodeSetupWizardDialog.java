package application.module.node.gui.wizard;

import application.module.node.gui.wizard.WizardStep;
import application.utils.gui.GuiFontManager;
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

        GuiFontManager.applyDefaultFont(stepIndicator);
        JPanel indicatorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
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
        stepIndicator.setText("Step " + (controller.getCurrentIndex() + 1) + " of "
                + controller.getSteps().size() + " — " + step.getTitle());
        backButton.setEnabled(!controller.isFirstStep());
        nextButton.setText(controller.isLastStep() ? "Finish" : "Next");
        nextButton.requestFocusInWindow();
    }

    private void handleNext() {
        String error = controller.next();
        if (error != null) {
            JOptionPane.showMessageDialog(this, error, "Please fix the highlighted input",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (controller.isLastStep()) {
            handleFinish();
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