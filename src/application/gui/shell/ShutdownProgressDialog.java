package application.gui.shell;

import application.kernel.ApplicationShutdown;
import application.module.node.gui.animations.RotatingSvgIcon;
import application.utils.gui.GuiIcons;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modeless popup shown while the application shutdown sequence runs.
 * <p>
 * Displays a rotating Signum icon and the message
 * {@value #STATUS_MESSAGE}. The dialog is deliberately MODELESS so the EDT
 * stays free and the rotation animation keeps running while the
 * {@link ApplicationShutdown} sequence executes on a background thread.
 * </p>
 * <p>
 * Entry point: {@link #showAndExecuteShutdown(Window)} — idempotent,
 * thread-safe and headless-safe. The dialog cannot be closed by the user
 * (the shutdown cannot be aborted from the UI) and is disposed automatically
 * once the sequence completes, right before the JVM exits.
 * </p>
 *
 * @since 5.0
 */
public class ShutdownProgressDialog extends JDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShutdownProgressDialog.class);

    /** Dialog window title. */
    public static final String DIALOG_TITLE = "Shutting down";

    /** User-facing status text shown while the shutdown sequence runs. */
    public static final String STATUS_MESSAGE =
            "Shutdown in progress, this could take several minutes";

    /** Rotation speed of the Signum icon (rotations per second). */
    private static final double ROTATION_SPEED_HZ = 0.5;

    /** Icon size in pixels. */
    private static final int ICON_SIZE_PX = 64;

    /**
     * Guards against opening a second shutdown dialog for the same JVM
     * lifetime (e.g. Shutdown button + tray menu clicked in quick succession).
     * Never reset: a shutdown, once triggered, is terminal.
     */
    private static final AtomicBoolean DIALOG_ACTIVE = new AtomicBoolean(false);

    /** The rotating Signum icon (started on open, stopped on close). */
    private final RotatingSvgIcon rotatingIcon;

    /**
     * Creates the shutdown progress popup.
     *
     * @param owner the owner window (may be {@code null})
     */
    public ShutdownProgressDialog(Window owner) {
        super(owner, DIALOG_TITLE, ModalityType.MODELESS);
        this.rotatingIcon = new RotatingSvgIcon(ROTATION_SPEED_HZ);
        rotatingIcon.setPreferredSize(new Dimension(ICON_SIZE_PX, ICON_SIZE_PX));
        rotatingIcon.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Undecorated: no OS title bar at all, hence no close (X) button —
        // the shutdown cannot be interrupted, so the dialog carries its own
        // header instead. Must be set before the window is first shown.
        setUndecorated(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createLineBorder(resolveBorderColor()));
        root.add(buildHeaderPanel(), BorderLayout.NORTH);
        root.add(buildContentPanel(rotatingIcon), BorderLayout.CENTER);
        setContentPane(root);
        // No close button exists anymore; DO_NOTHING_ON_CLOSE also keeps
        // programmatic close requests (e.g. Esc) inert.
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        pack();
        setLocationRelativeTo(owner);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                rotatingIcon.start();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                rotatingIcon.stop();
            }
        });
    }

    /**
     * The red POWER_OFF glyph used as the shutdown marker of the dialog
     * (reuses the existing {@link GuiIcons#stopped(int)} lifecycle icon).
     *
     * @param size icon size in pixels
     * @return the glyph icon
     */
    public static Icon buildPowerOffGlyph(int size) {
        return GuiIcons.stopped(size);
    }

    /**
     * Builds the dialog header: red POWER_OFF glyph + bold dialog title.
     * <p>
     * Replaces the OS title bar (the dialog is undecorated, so there is no
     * title text and no close button). Public/static so the layout can be
     * unit-tested headlessly.
     * </p>
     *
     * @return the header panel
     */
    public static JPanel buildHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
        header.add(new JLabel(buildPowerOffGlyph(18)), BorderLayout.WEST);

        JLabel titleLabel = new JLabel(DIALOG_TITLE, SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        header.add(titleLabel, BorderLayout.CENTER);
        return header;
    }

    /**
     * Border color for the undecorated dialog: the theme's separator color
     * with a neutral gray fallback.
     */
    private static Color resolveBorderColor() {
        try {
            Color color = UIManager.getColor("Separator.separatorColor");
            if (color != null) {
                return color;
            }
        } catch (Exception ignored) {
            // UIManager not ready yet
        }
        return new Color(0xC4, 0xC4, 0xC4);
    }

    /**
     * Builds the content panel: status label + rotating Signum icon.
     * <p>
     * Public/static so the layout can be unit-tested headlessly (no window
     * creation required).
     * </p>
     *
     * @param rotatingIcon the icon instance whose animation the caller manages
     * @return the content panel
     */
    public static JPanel buildContentPanel(RotatingSvgIcon rotatingIcon) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel messageLabel = new JLabel(STATUS_MESSAGE);
        messageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(messageLabel);

        panel.add(Box.createRigidArea(new Dimension(0, 15)));
        panel.add(rotatingIcon);

        return panel;
    }

    /**
     * Shows the shutdown popup (when a GUI is available) and executes the
     * {@link ApplicationShutdown} sequence on a background thread so the
     * rotating icon keeps animating on the EDT.
     * <p>
     * Idempotent: if the shutdown sequence already started or a dialog is
     * already active, this call is a no-op. Headless-safe: without a GUI the
     * sequence simply runs on a background thread and the JVM exits.
     * </p>
     *
     * @param owner the preferred owner window; if {@code null}, the first
     *              visible {@link JFrame} is used
     */
    public static void showAndExecuteShutdown(Window owner) {
        ApplicationShutdown shutdown = ApplicationShutdown.getInstance();
        if (shutdown.isShutdownInitiated()) {
            // WARN (not DEBUG): a duplicate trigger must be visible in the log,
            // including in headless mode where there is no UI feedback.
            LOGGER.warn("Shutdown already in progress - ignoring duplicate shutdown request");
            return;
        }
        if (!DIALOG_ACTIVE.compareAndSet(false, true)) {
            LOGGER.warn("Shutdown dialog already active - ignoring duplicate trigger");
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            startSequence(shutdown, null);
            return;
        }

        Window resolvedOwner = owner != null ? owner : findVisibleFrame();
        SwingUtilities.invokeLater(() -> {
            ShutdownProgressDialog dialog = new ShutdownProgressDialog(resolvedOwner);
            startSequence(shutdown, dialog);
            dialog.setVisible(true);
        });
    }

    /**
     * Runs the shutdown sequence on a dedicated thread, then (on the EDT)
     * disposes the dialog and exits the JVM.
     */
    private static void startSequence(ApplicationShutdown shutdown, JDialog dialog) {
        Thread sequenceThread = new Thread(() -> {
            try {
                shutdown.executeShutdownSequence();
            } catch (Throwable t) {
                LOGGER.error("Unexpected error during shutdown sequence", t);
            } finally {
                SwingUtilities.invokeLater(() -> {
                    if (dialog != null && dialog.isDisplayable()) {
                        dialog.dispose();
                    }
                    System.exit(0);
                });
            }
        }, "ApplicationShutdown-Sequence");
        sequenceThread.start();
    }

    /**
     * Finds the first visible top-level application window (best-effort owner).
     */
    private static Window findVisibleFrame() {
        for (Frame frame : Frame.getFrames()) {
            if (frame instanceof JFrame && frame.isVisible()) {
                return frame;
            }
        }
        return null;
    }
}
