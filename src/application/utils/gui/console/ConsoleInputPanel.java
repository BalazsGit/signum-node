package application.utils.gui.console;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.function.Consumer;

import application.utils.gui.CustomDrawingComponent;
import application.utils.gui.CustomDrawings;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * Reusable command input panel with prompt label, text field, and send button.
 * <p>
 * Used by {@link UnifiedConsolePanel} for console command entry. The panel is
 * self-contained: it handles UI layout, action wiring, and exposes a clean
 * setter-based API for runtime configuration.
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * All public mutating methods delegate to the EDT when called off-thread.
 * </p>
 *
 * @see UnifiedConsolePanel
 * @see ConsolePanelConfiguration
 */
public final class ConsoleInputPanel extends JPanel {

    // ── UI Components ────────────────────────────────────────────────────

    /** Custom-drawn chevron-right icon as prompt (dynamically sized and colored) */
    private CustomDrawingComponent promptChevron;
    private JTextField commandField;
    private JButton sendButton;

    // ── Configuration ────────────────────────────────────────────────────

    /** Command handler injected at runtime (null = commands ignored) */
    private Consumer<String> commandHandler;

    /** When false, the panel is visually disabled and ignores input */
    private volatile boolean enabled = true;

    /** Animation duration in milliseconds (matches NodeConsolePanel standard) */
    private static final int ANIMATION_DURATION_MS = 250;

    /** Current animation timer (null when no animation in progress) */
    private Timer animationTimer;

    /** Tracks whether panel is logically visible (expanded state) */
    private boolean expanded = true;

    /** Callback triggered when collapse animation completes (used by position change) */
    private Runnable onCollapsedListener;

    // ── Constructor ──────────────────────────────────────────────────────

    /** Creates the panel and initializes all UI components. */
    public ConsoleInputPanel() {
        initUI();
    }

    // ── UI Initialization ────────────────────────────────────────────────

    /**
     * Builds the layout: [>] [textField] [Send]
     * Uses BorderLayout with tight horizontal packing.
     */
    private void initUI() {
        setLayout(new BorderLayout(4, 0));
        setBorder(new EmptyBorder(4, 8, 4, 8));

        // Prompt chevron: custom-drawn Chevron.STANDARD.RIGHT icon at normal size
        promptChevron = new CustomDrawingComponent(CustomDrawings.Chevron.Standard.RIGHT, 1.0f);
        promptChevron.setPreferredSize(new Dimension(24, 24));
        promptChevron.setToolTipText("Command prompt");

        // Command text field: fills available horizontal space
        commandField = new JTextField();
        commandField.setToolTipText("Enter command and press Enter or click Send");

        // Send button: fixed width, aligned right
        sendButton = new JButton("Send");
        sendButton.setToolTipText("Execute command");

        // Wire shared action listener
        ActionListener sendAction = this::executeCommand;
        commandField.addActionListener(sendAction);
        sendButton.addActionListener(sendAction);

        // Assemble: WEST=prompt chevron, CENTER=field, EAST=button
        add(promptChevron, BorderLayout.WEST);
        add(commandField, BorderLayout.CENTER);
        add(sendButton, BorderLayout.EAST);
    }

    // ── Command Execution ────────────────────────────────────────────────

    /**
     * Reads the command text, invokes the handler on a background thread,
     * then clears the field. Empty commands are silently ignored.
     */
    @SuppressWarnings("unchecked")
    private void executeCommand(ActionEvent unused) {
        String cmd = commandField.getText().trim();
        if (cmd.isEmpty() || commandHandler == null) {
            return;
        }

        // Execute on a background thread to avoid blocking the EDT
        new Thread(() -> {
            try {
                commandHandler.accept(cmd);
            } finally {
                // Clear field on EDT after execution completes
                SwingUtilities.invokeLater(() -> commandField.setText(""));
            }
        }, "console-command").start();
    }

    /**
     * Executes the current command text synchronously on the calling thread.
     * Useful for testing or when caller wants direct control over threading.
     */
    public void executeCommand() {
        String cmd = commandField.getText().trim();
        if (!cmd.isEmpty() && commandHandler != null) {
            commandHandler.accept(cmd);
            commandField.setText("");
        }
    }

    // ── Setter-based Configuration (runtime) ────────────────────────────

    /**
     * Sets the handler invoked when the user submits a command.
     *
     * @param handler the command processor (null to disable execution)
     */
    public void setCommandHandler(Consumer<String> handler) {
        this.commandHandler = handler;
    }

    /**
     * Enables or disables the panel. When disabled, all child components
     * are disabled and commands are not processed.
     *
     * @param enabled true to enable the panel
     */
    public void setEnabled(boolean enabled) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setEnabled(enabled));
            return;
        }
        this.enabled = enabled;
        commandField.setEnabled(enabled);
        sendButton.setEnabled(enabled);
        promptChevron.setEnabled(enabled);
        setOpaque(enabled);
    }

    /** @return the current text in the command field */
    public String getCommandText() {
        return commandField.getText();
    }

    /**
     * Sets the text in the command field.
     *
     * @param text the text to set
     */
    public void setCommandText(String text) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setCommandText(text));
            return;
        }
        commandField.setText(text);
    }

    /** @return true if the panel is currently enabled */
    public boolean isEnabled() {
        return enabled;
    }

    // ── Component Accessors ──────────────────────────────────────────────

    /** @return the JTextField for direct access (e.g., focus management) */
    public JTextField getCommandField() {
        return commandField;
    }

    /** @return the Send JButton */
    public JButton getSendButton() {
        return sendButton;
    }

    /**
     * Sets a callback invoked when collapse animation completes.
     * Used by {@link UnifiedConsolePanel} to reposition after collapse, then expand at new location.
     *
     * @param listener the callback (null to clear)
     */
    public void setOnCollapsedListener(Runnable listener) {
        this.onCollapsedListener = listener;
    }

    // ── Visibility with Animation Support ────────────────────────────────

    /**
     * Shows the panel with optional smooth expand animation.
     * Uses ease-out cubic interpolation over 250ms to match NodeConsolePanel standard.
     *
     * @param animate true to use smooth animation, false for instant show
     */
    public void show(boolean animate) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> show(animate));
            return;
        }
        if (animate) {
            animateExpand();
        } else {
            instantShow();
        }
    }

    /**
     * Hides the panel with optional smooth collapse animation.
     * Clears the command field when fully hidden.
     *
     * @param animate true to use smooth animation, false for instant hide
     */
    public void hide(boolean animate) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> hide(animate));
            return;
        }
        if (animate) {
            animateCollapse();
        } else {
            instantHide();
        }
    }

    /** Toggles visibility with animation. */
    public void toggle() {
        toggle(true);
    }

    /**
     * Toggles visibility with configurable animation.
     *
     * @param animate true to use smooth animation
     */
    public void toggle(boolean animate) {
        if (expanded) {
            hide(animate);
        } else {
            show(animate);
        }
    }

    /** Returns the current expanded state of this panel. */
    public boolean isExpanded() {
        return expanded;
    }

    // ── Visibility via height-only (no setVisible) ───────────────────────
    // Strategy: the panel is always visible=true. Hidden state is achieved by
    // setting preferredSize/maximumSize to height=0 and border to zero insets.
    // This avoids flickering, layout conflicts, and works on every toggle.

    private void instantShow() {
        stopAnimation();
        expanded = true;
        setVisible(true);
        setPreferredSize(null);            // Natural size
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        setBorder(new EmptyBorder(4, 8, 4, 8));
        revalidateAndRepaintParents();
    }

    private void instantHide() {
        stopAnimation();
        expanded = false;
        commandField.setText("");
        setVisible(true);                  // Stay visible for layout stability
        setPreferredSize(new Dimension(0, 0));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 0));
        setBorder(new EmptyBorder(0, 0, 0, 0));
        revalidateAndRepaintParents();
    }

    private void animateExpand() {
        stopAnimation();
        expanded = true;
        setVisible(true);

        // FIX: After hide(), preferredSize is (0,0) and getHeight() also returns 0,
        // so getNaturalHeight() would incorrectly return -1 → skipping animation.
        // Solution: temporarily restore natural size to let the layout manager
        // recalculate the correct preferred height BEFORE measuring target.
        // This pattern was used in the legacy NodeConsolePanel.animateCommandPanelOpen().
        setPreferredSize(null);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        setBorder(new EmptyBorder(4, 8, 4, 8));
        revalidate();

        // Now the layout manager has recalculated and we get the correct natural height
        final int targetHeight = getNaturalHeight();
        if (targetHeight <= 0) {
            instantShow();
            return;
        }

        // Start animation from zero height
        setPreferredSize(new Dimension(getWidth(), 0));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 0));
        setBorder(new EmptyBorder(0, 0, 0, 0));
        revalidateAndRepaintParents();

        final long startTime = System.currentTimeMillis();
        animationTimer = new Timer(10, null);
        animationTimer.addActionListener(e -> {
            float rawProgress = Math.min(1.0f,
                    (System.currentTimeMillis() - startTime) / (float) ANIMATION_DURATION_MS);
            float progress = 1.0f - (float) Math.pow(1.0f - rawProgress, 3);  // ease-out cubic
            int height = (int) (targetHeight * progress);
            setPreferredSize(new Dimension(getWidth(), height));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            setBorder(new EmptyBorder(4, 8, 4, 8));
            revalidateAndRepaintParents();
            if (progress >= 1.0f) {
                animationTimer.stop();
                animationTimer = null;
                // Final state: restore natural size for correct layout behavior
                setPreferredSize(null);
                setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
                revalidateAndRepaintParents();
            }
        });
        animationTimer.start();
    }

    private void animateCollapse() {
        stopAnimation();
        expanded = false;

        final int startHeight = getHeight();
        if (startHeight <= 0) {
            instantHide();
            return;
        }

        final long startTime = System.currentTimeMillis();
        animationTimer = new Timer(10, null);
        animationTimer.addActionListener(e -> {
            float rawProgress = Math.min(1.0f,
                    (System.currentTimeMillis() - startTime) / (float) ANIMATION_DURATION_MS);
            float progress = 1.0f - (float) Math.pow(1.0f - rawProgress, 3);  // ease-out cubic
            int height = (int) (startHeight * (1.0f - progress));
            setPreferredSize(new Dimension(getWidth(), Math.max(0, height)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(0, height)));
            revalidateAndRepaintParents();
            if (progress >= 1.0f) {
                animationTimer.stop();
                animationTimer = null;
                commandField.setText("");
                setVisible(true);                  // Stay visible
                setPreferredSize(new Dimension(0, 0));
                setMaximumSize(new Dimension(Integer.MAX_VALUE, 0));
                setBorder(new EmptyBorder(0, 0, 0, 0));
                revalidateAndRepaintParents();
                // Notify listener so caller can reposition before expand
                if (onCollapsedListener != null) {
                    onCollapsedListener.run();
                }
            }
        });
        animationTimer.start();
    }

    /**
     * Calculates the natural height of this panel by asking the layout manager
     * for its preferred size using component-preferred sizes.
     */
    private int getNaturalHeight() {
        // Ask the layout manager for preferred size
        Dimension pref = getPreferredSize();
        if (pref != null && pref.height > 0) {
            return pref.height;
        }
        // Fallback: use current height if available
        int h = getHeight();
        return h > 0 ? h : -1;
    }

    private void stopAnimation() {
        if (animationTimer != null && animationTimer.isRunning()) {
            animationTimer.stop();
            animationTimer = null;
        }
    }

    /**
     * Revalidates and repaints this panel and all ancestor containers up to the top.
     * Ensures layout managers (including MigLayout) respond to size changes during animation.
     */
    private void revalidateAndRepaintParents() {
        revalidate();
        repaint();
        Component parent = getParent();
        while (parent != null) {
            parent.revalidate();
            parent.repaint();
            parent = parent.getParent();
        }
    }
}
