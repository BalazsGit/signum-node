package application.module.browser.gui.dialogs;

import application.module.browser.engine.security.CertificateInspector;
import application.module.browser.util.UrlUtils;
import application.utils.i18n.I18n;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * S2 (F2): the certificate details dialog, opened from the toolbar's green
 * padlock. Runs the {@link CertificateInspector} handshake on a background
 * executor and renders the chain (subject, issuer, validity, SAN, algorithm)
 * into a monospace text area — loading, error and detail cards.
 * <p>
 * Must be shown from the EDT. Non-modal: the browser stays usable while the
 * handshake runs.
 */
public final class CertificateDetailsDialog extends JDialog {

    private static final String CARD_LOADING = "loading";
    private static final String CARD_ERROR = "error";
    private static final String CARD_DETAILS = "details";

    /**
     * S2: the open dialogs keyed by the inspected URL (EDT-only state). A
     * repeated click on the same lock must bring the already-open dialog to
     * the front instead of opening another one (and re-running the
     * handshake).
     */
    private static final Map<String, CertificateDetailsDialog> OPEN_BY_URL = new HashMap<>();

    /**
     * Creates and shows the dialog (the inspection starts immediately). When
     * a dialog for the same URL is already open it is brought to the front
     * instead of creating a new one.
     *
     * @param owner     the window the dialog is centered on
     * @param url       the https URL to inspect
     * @param inspector the inspector (production or test) instance
     * @param executor  a background executor for the blocking handshake
     */
    public static void show(Window owner, String url, CertificateInspector inspector,
                            ExecutorService executor) {
        CertificateDetailsDialog existing = OPEN_BY_URL.get(url);
        if (existing != null && existing.isShowing()) {
            raise(existing);
            return;
        }
        CertificateDetailsDialog dialog =
                new CertificateDetailsDialog(owner, url, inspector, executor);
        OPEN_BY_URL.put(url, dialog);
        dialog.setVisible(true);
    }

    /**
     * Brings an already-open dialog to the front. On Windows a plain
     * {@code toFront()} is not enough while the owner window is active, so
     * the always-on-top toggle forces the re-order first.
     */
    private static void raise(CertificateDetailsDialog dialog) {
        dialog.setAlwaysOnTop(true);
        dialog.setAlwaysOnTop(false);
        dialog.toFront();
        dialog.requestFocusInWindow();
    }

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final JTextArea details = new JTextArea();
    private final JLabel errorLabel = new JLabel("");

    private CertificateDetailsDialog(Window owner, String url, CertificateInspector inspector,
                                     ExecutorService executor) {
        super(owner, I18n.get("browser.cert.dialog.title", hostOrFallback(url)),
                Dialog.ModalityType.MODELESS);

        details.setEditable(false);
        Font base = UIManager.getFont("TextField.font");
        details.setFont(base != null
                ? new Font(Font.MONOSPACED, base.getStyle(), base.getSize())
                : new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(details);

        JPanel loadingCard = new JPanel(new FlowLayout(FlowLayout.CENTER));
        loadingCard.add(new JLabel(I18n.get("browser.cert.dialog.loading")));
        JPanel errorCard = new JPanel(new BorderLayout());
        errorLabel.setForeground(UIManager.getColor("Label.errorForeground"));
        errorCard.add(errorLabel, BorderLayout.CENTER);

        cardHost.add(loadingCard, CARD_LOADING);
        cardHost.add(errorCard, CARD_ERROR);
        cardHost.add(scroll, CARD_DETAILS);

        JButton close = new JButton(I18n.get("browser.cert.dialog.close"));
        close.addActionListener(e -> dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(close);

        JPanel content = new JPanel(new BorderLayout());
        content.add(cardHost, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        setSize(560, 420);
        setMinimumSize(new java.awt.Dimension(420, 280));
        setLocationRelativeTo(owner);

        // Drop this dialog from the registry when it closes, so the next
        // lock click opens a fresh one (the conditional remove only drops
        // the entry if it still points at this dialog).
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                OPEN_BY_URL.remove(url, CertificateDetailsDialog.this);
            }
        });

        executor.execute(() -> {
            CertificateInspector.Result result = inspector.inspect(url);
            SwingUtilities.invokeLater(() -> finish(result));
        });
    }

    private void finish(CertificateInspector.Result result) {
        if (!isShowing()) {
            return;
        }
        if (result == null || !result.isOk()) {
            errorLabel.setText(I18n.get("browser.cert.dialog.failed",
                    result != null && result.getFailureReason() != null
                            ? result.getFailureReason()
                            : "unknown"));
            cards.show(cardHost, CARD_ERROR);
            return;
        }
        details.setText(formatChain(result.getChain()));
        cards.show(cardHost, CARD_DETAILS);
    }

    private static String hostOrFallback(String url) {
        String host = UrlUtils.host(url);
        return host.isEmpty() ? "—" : host;
    }

    private static String formatChain(List<CertificateInspector.CertificateInfo> chain) {
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            CertificateInspector.CertificateInfo cert = chain.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("Certificate #").append(i + 1).append('\n');
            sb.append(indent(I18n.get("browser.cert.field.subject"))).append(cert.getSubject());
            sb.append('\n');
            sb.append(indent(I18n.get("browser.cert.field.issuer"))).append(cert.getIssuer());
            sb.append('\n');
            sb.append(indent(I18n.get("browser.cert.field.valid")))
                    .append(format.format(new Date(cert.getNotBefore().toEpochMilli())))
                    .append("  →  ")
                    .append(format.format(new Date(cert.getNotAfter().toEpochMilli())));
            sb.append('\n');
            sb.append(indent(I18n.get("browser.cert.field.san")))
                    .append(cert.getSubjectAltNames().isEmpty()
                            ? "—"
                            : String.join(", ", cert.getSubjectAltNames()));
            sb.append('\n');
            sb.append(indent(I18n.get("browser.cert.field.algorithm")))
                    .append(cert.getSignatureAlgorithm());
        }
        return sb.toString();
    }

    private static String indent(String label) {
        return "  " + label + ": ";
    }
}