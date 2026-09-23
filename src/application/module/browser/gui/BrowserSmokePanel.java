package application.module.browser.gui;

import application.module.browser.core.BrowserEngine;
import application.module.browser.core.BrowserEngineState;
import application.utils.i18n.I18n;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * F0 smoke screen of the browser module (plan §9 F0) — replaced by the full
 * {@code BrowserPanel} in F1.
 * <p>
 * Shows the engine lifecycle end to end: a "preparing" card while the engine
 * initializes, then a live windowed CEF view of {@code https://example.com};
 * an error card (with the failure reason) when initialization failed.
 * <p>
 * Must be constructed on the EDT (the {@code ApplicationKernel} calls
 * {@code Module.getUI()} there). Engine state callbacks arrive on non-EDT
 * threads and are pumped to the EDT.
 */
public class BrowserSmokePanel extends JPanel {

    private static final String CARD_PREPARING = "preparing";
    private static final String CARD_IDLE = "idle";
    private static final String CARD_ERROR = "error";
    private static final String CARD_BROWSER = "browser";

    private static final String SMOKE_URL = "https://example.com";

    private final BrowserEngine engine;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final JPanel browserCard = new JPanel(new BorderLayout());
    private final JPanel errorTitle = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JPanel errorReason = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private CefClient client;

    public BrowserSmokePanel(BrowserEngine engine) {
        super(new BorderLayout());
        this.engine = engine;

        cardHost.add(messageCard(I18n.get("browser.smoke.preparing"),
                I18n.get("browser.smoke.preparing.hint")), CARD_PREPARING);
        cardHost.add(messageCard(I18n.get("browser.smoke.idle"), null), CARD_IDLE);

        errorTitle.add(new JLabel(I18n.get("browser.smoke.failed.title")));
        errorReason.add(new JLabel(""));
        JPanel errorCard = new JPanel(new BorderLayout(0, 8));
        errorCard.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JPanel top = new JPanel(new GridLayout(0, 1));
        top.add(errorTitle);
        top.add(errorReason);
        errorCard.add(top, BorderLayout.CENTER);
        errorCard.add(new JLabel(I18n.get("browser.smoke.failed.hint")), BorderLayout.SOUTH);
        cardHost.add(errorCard, CARD_ERROR);

        cardHost.add(browserCard, CARD_BROWSER);
        add(cardHost, BorderLayout.CENTER);

        // Engine callbacks arrive on non-EDT threads -> pump to the EDT.
        engine.addStateListener(state -> SwingUtilities.invokeLater(this::render));
        render();
    }

    private void render() {
        BrowserEngineState state = engine.getState();
        switch (state) {
            case READY -> showBrowser();
            case FAILED -> {
                ((JLabel) errorReason.getComponent(0)).setText(
                        I18n.get("browser.smoke.failed.reason", engine.getFailureReason()));
                show(CARD_ERROR);
            }
            case SHUTTING_DOWN, SHUT_DOWN, IDLE -> show(CARD_IDLE);
            default -> show(CARD_PREPARING);
        }
    }

    /**
     * Creates the smoke browser exactly once, on the first READY render.
     */
    private void showBrowser() {
        if (client != null) {
            show(CARD_BROWSER);
            return;
        }
        client = engine.createClient();
        CefBrowser browser = client.createBrowser(SMOKE_URL, false, true);
        browser.createImmediately();
        Component uiComponent = browser.getUIComponent();
        uiComponent.setPreferredSize(new Dimension(640, 480));
        browserCard.add(uiComponent, BorderLayout.CENTER);
        browserCard.revalidate();
        browserCard.repaint();
        show(CARD_BROWSER);
    }

    private void show(String cardName) {
        cards.show(cardHost, cardName);
    }

    private static JPanel messageCard(String title, String hint) {
        JPanel card = new JPanel(new GridLayout(0, 1));
        card.setBorder(BorderFactory.createEmptyBorder(32, 32, 32, 32));
        JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
        card.add(titleLabel);
        if (hint != null) {
            card.add(new JLabel(hint, SwingConstants.CENTER));
        }
        return card;
    }
}
