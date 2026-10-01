package application.module.browser.gui;

import application.module.browser.model.tab.BrowserTab;
import application.module.browser.model.tab.TabController;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiManager;
import application.utils.gui.SpinnerIcon;
import application.utils.i18n.I18n;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The browser's tab container — a single {@link JTabbedPane} that owns
 * both the tab row <em>and</em> the page content (plan §4.1, the same
 * structure the application and node tabs follow). Every tab's component
 * is the tab's self-contained {@link BrowserTabView}; switching tabs is
 * the pane's own show/hide. That is the <b>only</b> mechanism that
 * isolates windowed (native) CEF canvases correctly — verified
 * empirically with real CEF browsers: z-order stacking does NOT work (a
 * native canvas ignores {@code setComponentZOrder}, so the last created
 * browser stayed on top of every tab), while show/hide works (hidden
 * canvases keep their size and render surface; re-shown tabs come back
 * painted, no reload, no 0×0).
 * <p>
 * The tab row is the L&amp;F's (FlatLaf) own, driven by client properties:
 * fixed {@value #TAB_WIDTH} px tabs
 * ({@code JTabbedPane.minimumTabWidth}/{@code maximumTabWidth}); the
 * per-tab close "X" ({@code TabbedPane.closeIcon} +
 * {@code JTabbedPane.tabClosable}, the click arrives through
 * {@code JTabbedPane.tabCloseCallback}); the new-tab "+" at the row's end
 * ({@code JTabbedPane.trailingComponent}); overflow is the L&amp;F's
 * {@code SCROLL_TAB_LAYOUT} (arrows inside the row).
 * <p>
 * Thread model: Swing UI object — use from the EDT only.
 */
public final class BrowserTabPane extends JTabbedPane {

    /** Fixed tab width (T6): tabs never stretch to fill the window. */
    static final int TAB_WIDTH = 220;
    /** Client property that maps a tab's view to its model id. */
    static final String TAB_ID = "browser.tabId";
    /** Favicon/placeholder size follows the app's toolbar icon size. */
    private static final int ICON_SIZE = Math.max(14, (int) Math.round(GuiConstants.getToolBarIconSize()));

    private final TabController controller;
    /** Tab id → its view (insertion order = the tab order). */
    private final Map<String, JComponent> views = new LinkedHashMap<>();
    /** Tab id → decoded favicon icon (the fork delivers favicons rarely). */
    private final Map<String, Icon> faviconCache = new HashMap<>();
    /** URL → letter-tile placeholder icon. */
    private final Map<String, Icon> tileCache = new HashMap<>();
    /** One spinner per loading tab (created on demand, shared by ticks). */
    private final Map<String, SpinnerIcon> spinners = new HashMap<>();
    /** Drives the loading spinners (one shared timer, not one per tab). */
    private final Timer spinnerTimer = new Timer(33, e -> tickSpinners());
    /** The new-tab "+" (the row's permanent trailing element). */
    private final JButton newTab;

    /** Suppresses the selection listener while the pane reconciles with the model. */
    private boolean syncing;

    /**
     * @param controller the tab SSOT (never null)
     */
    public BrowserTabPane(TabController controller) {
        super(TOP, SCROLL_TAB_LAYOUT);
        this.controller = controller;
        setBorder(BorderFactory.createEmptyBorder());
        // Same overflow policy as the node/profile tab strips (Appearance).
        setTabLayoutPolicy(GuiManager.getInstance().getTabLayoutPolicy());

        putClientProperty("TabbedPane.closeIcon", new XGlyph());
        putClientProperty("TabbedPane.tabCloseToolTipText", I18n.get("browser.tab.close.tooltip"));
        putClientProperty("JTabbedPane.tabClosable", Boolean.TRUE);
        putClientProperty("JTabbedPane.tabCloseCallback", (IntConsumer) index -> {
            String tabId = tabIdAt(index);
            if (tabId != null) {
                controller.closeTab(tabId);
            }
        });
        putClientProperty("JTabbedPane.minimumTabWidth", TAB_WIDTH);
        putClientProperty("JTabbedPane.maximumTabWidth", TAB_WIDTH);

        newTab = createNewTabButton();
        // Pin the "+" to the left end of the trailing band with WEST, not CENTER:
        // FlatLaf renders the trailingComponent and, with the default
        // TabbedPane.tabAreaAlignment=leading, stretches it to fill the leftover
        // tab-row width (from the last tab to the right edge). CENTER would let the
        // button expand to fill that whole band; WEST keeps it at its preferred
        // width, right after the last tab (the user wanted the "+" next to the
        // tabs, not at the far-right edge).
        JPanel plusWrap = new JPanel(new BorderLayout());
        plusWrap.setOpaque(false);
        plusWrap.add(newTab, BorderLayout.WEST);
        putClientProperty("JTabbedPane.trailingComponent", plusWrap);

        addChangeListener(e -> {
            if (syncing) {
                return;
            }
            int index = getSelectedIndex();
            if (index < 0) {
                return;
            }
            String tabId = tabIdAt(index);
            if (tabId != null) {
                controller.activateById(tabId);
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int index = indexAtLocation(e.getX(), e.getY());
                if (index < 0) {
                    return; // not on a tab
                }
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    String tabId = tabIdAt(index);
                    if (tabId != null) {
                        controller.closeTab(tabId); // T2: middle-click closes
                    }
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    showContextMenu(tabIdAt(index), e.getX(), e.getY()); // T9
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Public API (BrowserPanel)
    // ------------------------------------------------------------------

    /**
     * Hosts the tab's view at its model position: adds the tab when
     * missing (in the controller's order), refreshes its in-place state
     * (title, tooltip, icon) and selects it when the tab is active.
     */
    public void addTab(BrowserTab tab, JComponent view) {
        List<BrowserTab> tabs = controller.getTabs();
        int at = tabs.indexOf(tab);
        if (at < 0) {
            return;
        }
        JComponent existing = views.get(tab.getId());
        if (existing == null) {
            views.put(tab.getId(), view);
            view.putClientProperty(TAB_ID, tab.getId());
            insertTab(displayTitle(tab), null, view, tooltipFor(tab), at);
        }
        updateTabState(tab);
        selectActive();
    }

    /** Removes the tab (the caller disposes the view's engine). */
    public void removeTab(String tabId) {
        JComponent view = views.remove(tabId);
        if (view == null) {
            return;
        }
        faviconCache.remove(tabId);
        spinners.remove(tabId);
        int at = indexOfComponent(view);
        if (at >= 0) {
            removeTabAt(at);
        }
    }

    /**
     * Reconciles the pane with the controller: removes closed tabs, adds
     * missing ones (in model order), reorders them and refreshes every
     * title/tooltip/icon state and the selection.
     */
    public void refresh() {
        List<BrowserTab> tabs = controller.getTabs();
        syncing = true;
        try {
            for (String id : new ArrayList<>(views.keySet())) {
                if (tabs.stream().noneMatch(t -> t.getId().equals(id))) {
                    JComponent view = views.remove(id);
                    faviconCache.remove(id);
                    spinners.remove(id);
                    int at = indexOfComponent(view);
                    if (at >= 0) {
                        removeTabAt(at);
                    }
                }
            }
            for (int i = 0; i < tabs.size(); i++) {
                BrowserTab tab = tabs.get(i);
                JComponent view = views.get(tab.getId());
                if (view == null) {
                    continue; // a viewless tab (engine not ready) stays viewless
                }
                int at = indexOfComponent(view);
                if (at < 0) {
                    insertTab(displayTitle(tab), null, view, tooltipFor(tab), i);
                } else if (at != i) {
                    removeTabAt(at);
                    insertTab(displayTitle(tab), null, view, tooltipFor(tab), i);
                }
            }
            for (BrowserTab tab : tabs) {
                if (views.containsKey(tab.getId())) {
                    updateTabState(tab);
                }
            }
            selectActive();
        } finally {
            syncing = false;
        }
    }

    /** Shows the given tab's view (the pane's show/hide does the rest). */
    public void showTab(String tabId) {
        JComponent view = views.get(tabId);
        if (view == null) {
            return;
        }
        int at = indexOfComponent(view);
        if (at >= 0 && getSelectedIndex() != at) {
            setSelectedIndex(at);
        }
    }

    /** The test/inspection hook: the tab count of the pane. */
    int tabCount() {
        return getTabCount();
    }

    /** The test/inspection hook: the tab title at the given index. */
    String titleAt(int index) {
        return getTitleAt(index);
    }

    /** The test/inspection hook: the selected index of the pane. */
    int selectedTabIndex() {
        return getSelectedIndex();
    }

    /** The test hook: the new-tab "+" button. */
    JButton newTabButton() {
        return newTab;
    }

    /** Stops the shared animation timer (call when the browser panel goes away). */
    public void dispose() {
        spinnerTimer.stop();
    }

    /**
     * Clears every tab and the per-tab state (engine shutdown): the
     * caller has already disposed the views' engines.
     */
    public void clearAll() {
        views.clear();
        faviconCache.clear();
        spinners.clear();
        tileCache.clear();
        removeAll();
    }

    // ------------------------------------------------------------------
    // Per-tab state
    // ------------------------------------------------------------------

    private void selectActive() {
        int active = controller.getActiveIndex();
        if (active >= 0 && active < getTabCount() && getSelectedIndex() != active) {
            setSelectedIndex(active);
        }
    }

    private void updateTabState(BrowserTab tab) {
        int at = indexOfTabId(tab.getId());
        if (at < 0) {
            return;
        }
        setTitleAt(at, displayTitle(tab));
        setToolTipTextAt(at, tooltipFor(tab));
        Icon icon = iconFor(tab);
        if (!icon.equals(getIconAt(at))) {
            setIconAt(at, icon);
        }
    }

    /** The tab's leading icon: loading spinner, favicon or letter tile. */
    private Icon iconFor(BrowserTab tab) {
        if (tab.isLoading()) {
            SpinnerIcon spinner = spinners.computeIfAbsent(tab.getId(),
                    k -> new SpinnerIcon(ICON_SIZE, accent()));
            if (!spinnerTimer.isRunning()) {
                spinnerTimer.start();
            }
            return spinner;
        }
        Icon cached = faviconCache.get(tab.getId());
        if (cached != null) {
            return cached;
        }
        byte[] favicon = tab.getFavicon();
        if (favicon != null && favicon.length > 0) {
            try {
                Image image = ImageIO.read(new ByteArrayInputStream(favicon));
                if (image != null) {
                    Icon icon = new ImageIcon(image);
                    faviconCache.put(tab.getId(), icon);
                    return icon;
                }
            } catch (Exception ignored) {
                // unreadable favicon bytes — fall back to the letter tile
            }
        }
        // F1: the pinned JCEF fork has no favicon callback, so the
        // deterministic letter tile is what users see.
        return tileCache.computeIfAbsent("tile:" + tab.getUrl(), k -> new LetterTileIcon(tab.getUrl()));
    }

    /** Advances every visible spinner; stops itself when nothing is loading. */
    private void tickSpinners() {
        boolean any = false;
        for (BrowserTab tab : controller.getTabs()) {
            if (!tab.isLoading()) {
                continue;
            }
            SpinnerIcon spinner = spinners.get(tab.getId());
            int at = indexOfTabId(tab.getId());
            if (spinner == null || at < 0) {
                continue;
            }
            spinner.advance(20f);
            setIconAt(at, spinner);
            any = true;
        }
        if (!any) {
            spinnerTimer.stop();
        }
    }

    // ------------------------------------------------------------------
    // Context menu (T9)
    // ------------------------------------------------------------------

    /**
     * The tab's context menu (right-click): close, close others, close
     * tabs to the right, copy the page address.
     */
    private void showContextMenu(String tabId, int x, int y) {
        if (tabId == null) {
            return;
        }
        BrowserTab tab = controller.getTabAt(indexOfTabId(tabId)).orElse(null);
        if (tab == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem(I18n.get("browser.tab.context.close"),
                () -> controller.closeTab(tab.getId())));
        menu.add(menuItem(I18n.get("browser.tab.context.closeOthers"),
                () -> controller.closeOthers(tab.getId())));
        menu.add(menuItem(I18n.get("browser.tab.context.closeRight"),
                () -> controller.closeRightOf(tab.getId())));
        menu.addSeparator();
        menu.add(menuItem(I18n.get("browser.tab.context.copyUrl"), () ->
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new java.awt.datatransfer.StringSelection(tab.getUrl()), null)));
        menu.show(this, x, y);
    }

    private static JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The new-tab "+" button (the row's permanent trailing element). */
    private JButton createNewTabButton() {
        JButton button = new NewTabButton();
        button.addActionListener(e -> controller.openNewTab());
        return button;
    }

    /**
     * The new-tab "+" button: the same glyph/size as the close "X", but it
     * paints a rounded hover highlight on rollover — the same selection
     * behaviour the tab close "X" icons get from the L&F — so the "+" reads as
     * a clickable control like the close icons, not a flat glyph.
     */
    private static final class NewTabButton extends JButton {

        NewTabButton() {
            setIcon(new PlusGlyph());
            setToolTipText(I18n.get("browser.tab.new.tooltip"));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (getModel().isRollover()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hoverColor());
                g2.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
                g2.dispose();
            }
            super.paintComponent(g);
        }

        private static Color hoverColor() {
            Color c = UIManager.getColor("TabbedPane.hoverBackground");
            return c != null ? c : new Color(0x80, 0x80, 0x80, 48);
        }
    }

    /** The tab id at the given index, or {@code null}. */
    private String tabIdAt(int index) {
        if (index < 0 || index >= getTabCount()) {
            return null;
        }
        Component component = getComponentAt(index);
        return component instanceof JComponent c ? (String) c.getClientProperty(TAB_ID) : null;
    }

    /** The index of the given tab id, or -1. */
    private int indexOfTabId(String tabId) {
        for (int i = 0; i < getTabCount(); i++) {
            if (tabId.equals(tabIdAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The tab's tooltip: title, URL and (when discarded) a restore note. */
    private static String tooltipFor(BrowserTab tab) {
        String title = displayTitle(tab);
        String url = tab.getUrl();
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title);
        }
        if (url != null && !url.equals(title)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(url);
        }
        if (tab.isDiscarded()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(I18n.get("browser.tab.discarded"));
        }
        return sb.toString();
    }

    /** The tab's display title (title, else the New-Tab name, else the host). */
    static String displayTitle(BrowserTab tab) {
        if (tab.getTitle() != null && !tab.getTitle().isBlank()) {
            return tab.getTitle();
        }
        String url = tab.getUrl();
        if (TabController.NEW_TAB_URL.equals(url)) {
            return I18n.get("browser.tab.title.newtab");
        }
        String host = hostOf(url);
        return host != null ? host : (url != null ? url : "");
    }

    /** The URL's host (signum:// scheme included), or {@code null}. */
    static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            if (url.startsWith("signum://")) {
                return url.substring("signum://".length()).split("[/?]")[0];
            }
            URI uri = URI.create(url);
            if (uri.getHost() != null) {
                return uri.getHost();
            }
        } catch (Exception ignored) {
            // not a parseable URL — fall through
        }
        return null;
    }

    /** The L&F's accent color (theme-aware), with a neutral fallback. */
    private static Color accent() {
        Color c = UIManager.getColor("Component.focusColor");
        return c != null ? c : new Color(0x4A, 0x9E, 0xD9);
    }

    // ------------------------------------------------------------------
    // Icons
    // ------------------------------------------------------------------

    /**
     * The deterministic placeholder shown when the engine delivered no
     * favicon (F1): a hue derived from the URL hash, the host's first letter.
     */
    private final class LetterTileIcon implements Icon {

        private final BufferedImage image;

        LetterTileIcon(String url) {
            int size = ICON_SIZE;
            image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = image.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                String host = hostOf(url);
                String letter = (host != null && !host.isEmpty()) ? host.substring(0, 1).toUpperCase() : "?";
                int hue = Math.floorMod(url != null ? url.hashCode() : 0, 360);
                g2.setColor(Color.getHSBColor(hue / 360f, 0.35f, 0.45f));
                g2.fillRoundRect(0, 0, size, size, 5, 5);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12).deriveFont(size * 0.62f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(letter,
                        (size - fm.stringWidth(letter)) / 2,
                        (size + fm.getAscent() - fm.getDescent()) / 2 - 1);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return ICON_SIZE;
        }

        @Override
        public int getIconHeight() {
            return ICON_SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.drawImage(image, x, y, null);
        }
    }

    /** The tab's close "X" glyph (8 px cross, theme-aware color). */
    private static final class XGlyph implements Icon {

        private static final int SIZE = 16;

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color());
            g2.setStroke(new BasicStroke(1.4f));
            int cx = x + SIZE / 2;
            int cy = y + SIZE / 2;
            g2.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
            g2.drawLine(cx - 4, cy + 4, cx + 4, cy - 4);
            g2.dispose();
        }

        private static Color color() {
            Color c = UIManager.getColor("controlText");
            return c != null ? c : new Color(0x6B, 0x6D, 0x72);
        }
    }

    /** The new-tab "+" glyph (the same size and stroke as the close "X"). */
    private static final class PlusGlyph implements Icon {

        private static final int SIZE = 16;

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color());
            g2.setStroke(new BasicStroke(1.4f));
            int cx = x + SIZE / 2;
            int cy = y + SIZE / 2;
            g2.drawLine(cx - 4, cy, cx + 4, cy);
            g2.drawLine(cx, cy - 4, cx, cy + 4);
            g2.dispose();
        }

        private static Color color() {
            Color c = UIManager.getColor("controlText");
            return c != null ? c : new Color(0x20, 0x21, 0x24);
        }
    }
}