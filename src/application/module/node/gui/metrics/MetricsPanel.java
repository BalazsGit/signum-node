package application.module.node.gui.metrics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import application.utils.gui.CustomDrawingComponent;
import application.utils.gui.CustomDrawings;
import application.utils.gui.TabbedPaneHoverHelper;

@SuppressWarnings("serial")
public class MetricsPanel extends JTabbedPane {

    private final SynchronizationMetricsPanel syncPanel;
    private final BlockGenerationMetricsPanel blockGenPanel;
    private final PeerMetricsPanel peerMetricsPanel;
    private final NetworkMetricsPanel networkMetricsPanel;
    private final JPanel syncWrapper;
    private final JPanel blockGenWrapper;
    private final JPanel peerWrapper;
    private final JPanel networkWrapper;
    private boolean isExpanded = true;
    private int lastSelectedIndex = 1;
    private final CustomDrawingComponent toggleTab;
    private Timer animationTimer;
    private final TabbedPaneHoverHelper hoverHelper = new TabbedPaneHoverHelper();

    // Dedicated executors for each panel to ensure isolation and prevent starvation
    private final ExecutorService syncExecutor;
    private final ExecutorService blockGenExecutor;
    private final ExecutorService peerExecutor;
    private final ExecutorService networkExecutor;

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsPanel.class);

    public MetricsPanel(JFrame parentFrame) {
        // Create dedicated single thread executors for each panel.
        // This ensures that heavy load on one panel (e.g. PeerMetrics) does not block
        // updates on other panels (e.g. Sync), providing better UI responsiveness.

        syncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Sync-Metrics-Worker");
            t.setDaemon(true);
            return t;
        });

        blockGenExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BlockGen-Metrics-Worker");
            t.setDaemon(true);
            return t;
        });

        peerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Peer-Metrics-Worker");
            t.setDaemon(true);
            return t;
        });

        networkExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Network-Metrics-Worker");
            t.setDaemon(true);
            return t;
        });

        syncPanel = new SynchronizationMetricsPanel(parentFrame, syncExecutor);
        blockGenPanel = new BlockGenerationMetricsPanel(parentFrame, blockGenExecutor);
        peerMetricsPanel = new PeerMetricsPanel(parentFrame, peerExecutor);
        networkMetricsPanel = new NetworkMetricsPanel(parentFrame, networkExecutor);

        // Create wrappers for collapsing animation
        syncWrapper = new JPanel(new BorderLayout()) {
            @Override
            public boolean isValidateRoot() {
                return true;
            }
        };
        JScrollPane syncScrollPane = new JScrollPane(syncPanel) {
            @Override
            public Dimension getPreferredSize() {
                Dimension pref = super.getPreferredSize();
                JScrollBar hBar = getHorizontalScrollBar();
                if (hBar != null && (hBar.isVisible() || hBar.isShowing())) {
                    pref.height += hBar.getPreferredSize().height;
                }
                return pref;
            }
        };
        syncScrollPane.setBorder(BorderFactory.createEmptyBorder());
        syncScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        syncScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        syncWrapper.add(syncScrollPane, BorderLayout.CENTER);

        blockGenWrapper = new JPanel(new BorderLayout()) {
            @Override
            public boolean isValidateRoot() {
                return true;
            }
        };
        JScrollPane blockGenScrollPane = new JScrollPane(blockGenPanel) {
            @Override
            public Dimension getPreferredSize() {
                Dimension pref = super.getPreferredSize();
                JScrollBar hBar = getHorizontalScrollBar();
                if (hBar != null && (hBar.isVisible() || hBar.isShowing())) {
                    pref.height += hBar.getPreferredSize().height;
                }
                return pref;
            }
        };
        blockGenScrollPane.setBorder(BorderFactory.createEmptyBorder());
        blockGenScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        blockGenScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        blockGenWrapper.add(blockGenScrollPane, BorderLayout.CENTER);

        peerWrapper = new JPanel(new BorderLayout()) {
            @Override
            public boolean isValidateRoot() {
                return true;
            }
        };
        JScrollPane peerScrollPane = new JScrollPane(peerMetricsPanel) {
            @Override
            public Dimension getPreferredSize() {
                Dimension pref = super.getPreferredSize();
                JScrollBar hBar = getHorizontalScrollBar();
                if (hBar != null && (hBar.isVisible() || hBar.isShowing())) {
                    pref.height += hBar.getPreferredSize().height;
                }
                return pref;
            }
        };
        peerScrollPane.setBorder(BorderFactory.createEmptyBorder());
        peerScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        peerScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        peerWrapper.add(peerScrollPane, BorderLayout.CENTER);

        networkWrapper = new JPanel(new BorderLayout()) {
            @Override
            public boolean isValidateRoot() {
                return true;
            }
        };
        JScrollPane networkScrollPane = new JScrollPane(networkMetricsPanel) {
            @Override
            public Dimension getPreferredSize() {
                Dimension pref = super.getPreferredSize();
                JScrollBar hBar = getHorizontalScrollBar();
                if (hBar != null && (hBar.isVisible() || hBar.isShowing())) {
                    pref.height += hBar.getPreferredSize().height;
                }
                return pref;
            }
        };
        networkScrollPane.setBorder(BorderFactory.createEmptyBorder());
        networkScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        networkScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        networkWrapper.add(networkScrollPane, BorderLayout.CENTER);
        setTabPlacement(JTabbedPane.BOTTOM);

        // Tab 0: Toggle
        addTab(null, null);
        setToolTipTextAt(0, "Toggle Metrics Panel");
        setEnabledAt(0, false);

        toggleTab = new CustomDrawingComponent(isExpanded ? CustomDrawings.Chevron.UP : CustomDrawings.Chevron.DOWN);
        setTabComponentAt(0, toggleTab);
        toggleTab.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                toggleExpanded();
            }
        });

        // Tab 1: Sync
        addTab("Sync", syncWrapper);

        // Tab 2: Block Gen
        addTab("Block Gen", blockGenWrapper);

        // Tab 3: Peer Metrics
        addTab("Peer Metrics", peerWrapper);

        // Tab 4: Network
        addTab("Network", networkWrapper);

        setSelectedIndex(1); // Default to Sync

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int tabIndex = indexAtLocation(e.getX(), e.getY());
                if (tabIndex == 0) {
                    toggleExpanded();
                }
            }
        });

        addChangeListener(e -> {
            if (!isExpanded && getSelectedIndex() != 0 && (animationTimer == null || !animationTimer.isRunning())) {
                toggleExpanded();
            }
        });
    }

    private void toggleExpanded() {
        if (animationTimer != null && animationTimer.isRunning()) {
            animationTimer.stop();
        }

        boolean expanding = !isExpanded;
        isExpanded = expanding;
        toggleTab.setDrawing(isExpanded ? CustomDrawings.Chevron.UP : CustomDrawings.Chevron.DOWN);

        int targetHeight;
        int startHeight;

        // Calculate max natural height of wrappers (containing scrollpanes)
        setWrappersHeight(null);
        int h1 = syncWrapper.getPreferredSize().height;
        int h2 = blockGenWrapper.getPreferredSize().height;
        int h3 = peerWrapper.getPreferredSize().height;
        int h4 = networkWrapper.getPreferredSize().height;
        int naturalHeight = Math.max(h1, Math.max(h2, Math.max(h3, h4)));

        if (expanding) {
            startHeight = 0;
            targetHeight = naturalHeight;

            if (getSelectedIndex() == 0) {
                if (lastSelectedIndex > 0 && lastSelectedIndex < getTabCount()) {
                    setSelectedIndex(lastSelectedIndex);
                } else {
                    setSelectedIndex(1);
                }
            }
        } else {
            // Force a layout pass to ensure wrapper heights are accurate before reading them.
            // This is critical on initial load when applyPanelVisibilityState() added the panel
            // but wrappers may not have been fully laid out yet, causing startHeight to be
            // calculated incorrectly (collapse animation only goes "halfway").
            this.doLayout();
            revalidate();

            // Use actual visible height as start point for collapse animation to avoid
            // "invisible" start
            startHeight = Math.max(syncWrapper.getHeight(), Math.max(blockGenWrapper.getHeight(),
                    Math.max(peerWrapper.getHeight(), networkWrapper.getHeight())));
            targetHeight = 0;
            if (getSelectedIndex() != 0) {
                lastSelectedIndex = getSelectedIndex();
            }
        }

        final int finalStartHeight = startHeight;
        final int finalTargetHeight = targetHeight;
        final long startTime = System.currentTimeMillis();
        final int duration = 250;

        animationTimer = new Timer(10, e -> {
            long now = System.currentTimeMillis();
            float fraction = (float) (now - startTime) / duration;
            if (fraction >= 1f) {
                fraction = 1f;
                animationTimer.stop();

                if (expanding) {
                    setWrappersHeight(null); // Reset to natural size
                } else {
                    setWrappersHeight(0);
                    setSelectedIndex(0);
                }
            } else {
                // Cubic ease out
                fraction = 1f - (float) Math.pow(1f - fraction, 3);
                int currentH = (int) (finalStartHeight + (finalTargetHeight - finalStartHeight) * fraction);
                setWrappersHeight(currentH);
            }
            revalidate();
            repaint();
        });

        // Set initial state for animation
        setWrappersHeight(startHeight);

        animationTimer.start();
        toggleTab.repaint();
    }

    private void setWrappersHeight(Integer height) {
        updateWrapperHeight(syncWrapper, syncPanel, height);
        updateWrapperHeight(blockGenWrapper, blockGenPanel, height);
        updateWrapperHeight(peerWrapper, peerMetricsPanel, height);
        updateWrapperHeight(networkWrapper, networkMetricsPanel, height);
    }

    private void updateWrapperHeight(JPanel wrapper, JComponent content, Integer height) {
        if (height == null) {
            wrapper.setPreferredSize(null);
            wrapper.setMinimumSize(null);
        } else {
            wrapper.setPreferredSize(new Dimension(content.getPreferredSize().width, height));
            // Allow horizontal shrinking even during vertical animation/collapse to prevent
            // toolbar right-side icons from being pushed out of the window.
            wrapper.setMinimumSize(new Dimension(0, height));
        }
    }

    public void init() {
        syncPanel.init();
        blockGenPanel.init();
        peerMetricsPanel.init();
        networkMetricsPanel.init();
    }

    public void shutdown() {
        try {
            syncPanel.shutdown();
        } catch (Throwable t) {
            LOGGER.warn("Error shutting down sync panel", t);
        }
        try {
            blockGenPanel.shutdown();
        } catch (Throwable t) {
            LOGGER.warn("Error shutting down block generation panel", t);
        }
        try {
            peerMetricsPanel.shutdown();
        } catch (Throwable t) {
            LOGGER.warn("Error shutting down peer metrics panel", t);
        }
        try {
            networkMetricsPanel.shutdown();
        } catch (Throwable t) {
            LOGGER.warn("Error shutting down network metrics panel", t);
        }

        shutdownExecutor(syncExecutor, "sync");
        shutdownExecutor(blockGenExecutor, "blockGen");
        shutdownExecutor(peerExecutor, "peer");
        shutdownExecutor(networkExecutor, "network");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        try {
            executor.shutdownNow();
        } catch (Throwable t) {
            LOGGER.warn("Error shutting down " + name + " executor", t);
        }
    }

    public void setUiOptimizationEnabled(boolean enabled) {
        syncPanel.setUiOptimizationEnabled(enabled);
        blockGenPanel.setUiOptimizationEnabled(enabled);
        peerMetricsPanel.setUiOptimizationEnabled(enabled);
        networkMetricsPanel.setUiOptimizationEnabled(enabled);
    }

    /**
     * Overrides repaint to check and update the hover state
     * whenever a repaint is requested, if the mouse is over the tabs.
     */
    @Override
    public void repaint(long tm, int x, int y, int width, int height) {
        // Do not update hover during animation to avoid overloading the EDT
        if (hoverHelper != null && (animationTimer == null || !animationTimer.isRunning())) {
            hoverHelper.handleRepaint(this);
        }
        super.repaint(tm, x, y, width, height);
    }

    /**
     * Overrides revalidate as layout changes (e.g., animation or content updates)
     * often cause the loss of hover state.
     */
    @Override
    public void revalidate() {
        super.revalidate();
        // Csak akkor szinkronizáljunk hovert, ha nincs mozgásban a panel
        if (hoverHelper != null && (animationTimer == null || !animationTimer.isRunning())) {
            hoverHelper.handleRevalidate(this);
        }
    }
}