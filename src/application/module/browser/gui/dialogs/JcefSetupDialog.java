package application.module.browser.gui.dialogs;

import application.module.browser.core.JcefProvisioner;
import application.utils.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The JCEF setup dialog: shown (non-modal) when the engine fails with
 * {@code MISSING_JCEF} on a platform where the auto-install is supported.
 * Explains that the browser component must be installed, and on consent runs
 * the {@link JcefProvisioner} (download + SHA-256 verify + extract + install)
 * on a background thread with a progress bar.
 * <p>
 * On success it disposes itself and invokes the caller's callback (the browser
 * panel retries the engine init — a FAILED engine is retryable). Cancellation
 * at any point just aborts the download and returns the dialog to its idle
 * state. Must be created on the EDT.
 */
public final class JcefSetupDialog extends JDialog {

    private static final Logger logger = LoggerFactory.getLogger(JcefSetupDialog.class);

    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel(I18n.get("browser.jcef.setup.idle"), SwingConstants.LEFT);
    private final JButton installButton = new JButton(I18n.get("browser.jcef.setup.install"));
    private final JButton cancelButton = new JButton(I18n.get("browser.jcef.setup.cancel"));
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Runnable onSuccess;
    private volatile ExecutorService executor;

    /**
     * Creates and shows the dialog.
     *
     * @param owner     the window the dialog is centered on
     * @param onSuccess invoked (on the EDT) after a successful install
     */
    public static void show(Window owner, Runnable onSuccess) {
        try {
            new JcefSetupDialog(owner, onSuccess).setVisible(true);
        } catch (RuntimeException e) {
            // A broken dialog must never kill the EDT: the browser is an
            // optional module — the FAILED-engine card (with the manual
            // install hint) stays up and the rest of the app keeps working.
            logger.error("Failed to show the JCEF setup dialog", e);
        }
    }

    private JcefSetupDialog(Window owner, Runnable onSuccess) {
        super(owner, I18n.get("browser.jcef.setup.title"), Dialog.ModalityType.MODELESS);
        this.onSuccess = onSuccess;

        // The dialog's own font may be NULL: JDialog picks up the L&F's
        // "Dialog.font", which the default (Metal) L&F does not define — so
        // getFontMetrics(getFont()) NPEs on the EDT and takes the whole GUI
        // down. Use the L&F label font (JDK default as last resort) for all
        // metrics and derived fonts.
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.DIALOG, Font.PLAIN, 12);
        }
        JLabel titleLabel = new JLabel(I18n.get("browser.jcef.setup.title"));
        titleLabel.setFont(base.deriveFont(Font.BOLD, base.getSize() + 2f));

        // Wrapped info label (the install hint must stay readable; long unbreakable
        // tokens — e.g. the Windows target path — are broken across lines instead
        // of widening the dialog to that token's full width).
        FontMetrics fm = getFontMetrics(base);
        JLabel infoLabel = new JLabel(
                wrapText(I18n.get("browser.jcef.setup.info", JcefProvisioner.targetDir()), fm, 440),
                SwingConstants.LEFT);
        infoLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        progressBar.setStringPainted(true);
        progressBar.setIndeterminate(false);

        JPanel progressArea = new JPanel(new BorderLayout(0, 4));
        progressArea.add(progressBar, BorderLayout.CENTER);
        progressArea.add(statusLabel, BorderLayout.SOUTH);
        progressArea.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

        installButton.addActionListener(e -> startInstall());
        cancelButton.addActionListener(e -> {
            if (isRunning()) {
                cancelled.set(true);
            } else {
                dispose();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(installButton);
        buttons.add(cancelButton);

        JPanel top = new JPanel(new GridLayout(0, 1, 0, 8));
        top.setOpaque(false);
        top.add(titleLabel);
        top.add(infoLabel);
        JPanel content = new JPanel(new BorderLayout(0, 0));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        content.add(top, BorderLayout.NORTH);
        content.add(progressArea, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Word-wraps {@code text} to {@code width} px; a single token wider than the
     * line (e.g. a long Windows path) is broken character-by-character instead of
     * forcing the dialog to that token's width.
     */
    private static String wrapText(String text, FontMetrics fm, int width) {
        StringBuilder wrapped = new StringBuilder();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            while (fm.stringWidth(word) > width) {
                if (line.length() > 0) {
                    wrapped.append(line).append('\n');
                    line.setLength(0);
                }
                int cut = word.length();
                while (cut > 1 && fm.stringWidth(word.substring(0, cut)) > width) {
                    cut--;
                }
                line.append(word, 0, cut);
                word = word.substring(cut);
            }
            if (line.length() > 0 && fm.stringWidth(line + " " + word) > width) {
                wrapped.append(line).append('\n');
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            wrapped.append(line);
        }
        return wrapped.toString();
    }

    private boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    private void startInstall() {
        installButton.setEnabled(false);
        cancelButton.setText(I18n.get("browser.jcef.setup.abort"));
        cancelled.set(false);
        statusLabel.setText(I18n.get("browser.jcef.setup.downloading.start"));
        progressBar.setIndeterminate(true);
        progressBar.setValue(0);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jcef-provision");
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> {
            try {
                JcefProvisioner.provision(this::onProgress);
                onDone();
            } catch (JcefProvisioner.ProvisionCancelledException e) {
                onAborted();
            } catch (Exception e) {
                onError(e);
            } finally {
                executor.shutdown();
            }
        });
    }

    /** Provisioning thread — pump to the EDT. */
    private void onProgress(JcefProvisioner.Phase phase, double fraction, long done, long total) {
        SwingUtilities.invokeLater(() -> {
            switch (phase) {
                case DOWNLOADING -> {
                    if (fraction >= 0) {
                        progressBar.setIndeterminate(false);
                        progressBar.setValue((int) (fraction * 100));
                    }
                    statusLabel.setText(I18n.get("browser.jcef.setup.downloading",
                            total > 0 ? (int) (fraction * 100) : 0,
                            done / (1024 * 1024), total / (1024 * 1024)));
                }
                case VERIFYING -> {
                    progressBar.setIndeterminate(true);
                    statusLabel.setText(I18n.get("browser.jcef.setup.verifying"));
                }
                case EXTRACTING -> {
                    progressBar.setIndeterminate(true);
                    statusLabel.setText(I18n.get("browser.jcef.setup.extracting"));
                }
                case INSTALLING -> {
                    progressBar.setIndeterminate(true);
                    statusLabel.setText(I18n.get("browser.jcef.setup.installing"));
                }
            }
        });
    }

    private void onDone() {
        SwingUtilities.invokeLater(() -> {
            progressBar.setIndeterminate(false);
            progressBar.setValue(100);
            statusLabel.setText(I18n.get("browser.jcef.setup.done"));
            dispose();
            onSuccess.run();
        });
    }

    private void onAborted() {
        SwingUtilities.invokeLater(() -> {
            progressBar.setIndeterminate(false);
            progressBar.setValue(0);
            statusLabel.setText(I18n.get("browser.jcef.setup.idle"));
            installButton.setEnabled(true);
            cancelButton.setText(I18n.get("browser.jcef.setup.cancel"));
        });
    }

    private void onError(Exception e) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setIndeterminate(false);
            progressBar.setValue(0);
            statusLabel.setText(I18n.get("browser.jcef.setup.error",
                    e.getMessage() != null ? e.getMessage() : e.toString()));
            installButton.setEnabled(true);
            cancelButton.setText(I18n.get("browser.jcef.setup.cancel"));
        });
    }
}