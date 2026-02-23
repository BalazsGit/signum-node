package brs.gui;

import brs.gui.util.CustomDrawings;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("serial")
public class MetricsPanel extends JTabbedPane {

    private final SynchronizationMetricsPanel syncPanel;
    private final BlockGenerationMetricsPanel blockGenPanel;
    private final PeerMetricsPanel peerMetricsPanel;
    private boolean isExpanded = true;
    private final JComponent toggleTab;

    // Dedicated executors for each panel to ensure isolation and prevent starvation
    private final ExecutorService syncExecutor;
    private final ExecutorService blockGenExecutor;
    private final ExecutorService peerExecutor;

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

        syncPanel = new SynchronizationMetricsPanel(parentFrame, syncExecutor);
        blockGenPanel = new BlockGenerationMetricsPanel(parentFrame, blockGenExecutor);
        peerMetricsPanel = new PeerMetricsPanel(peerExecutor);

        // Tabs at the bottom
        setTabPlacement(JTabbedPane.BOTTOM);

        // Tab 0: Toggle
        addTab(null, null);
        setToolTipTextAt(0, "Toggle Metrics Panel");
        setEnabledAt(0, false);

        toggleTab = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                (isExpanded ? CustomDrawings.Chevron.UP : CustomDrawings.Chevron.DOWN)
                        .draw((Graphics2D) g, getWidth(), getHeight(), new Color(230, 230, 230));
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(20, 16);
            }
        };
        setTabComponentAt(0, toggleTab);

        // Tab 1: Sync
        addTab("Sync", syncPanel);

        // Tab 2: Block Gen
        addTab("Block Gen", blockGenPanel);

        // Tab 3: Peer Metrics
        addTab("Peer Metrics", peerMetricsPanel);

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
            if (!isExpanded) {
                toggleExpanded();
            }
        });
    }

    private void toggleExpanded() {
        isExpanded = !isExpanded;
        toggleTab.repaint();
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        if (!isExpanded) {
            Rectangle r = getBoundsAt(0);
            if (r != null) {
                d.height = r.height + getInsets().top + getInsets().bottom + 4;
            } else {
                d.height = 30;
            }
        }
        return d;
    }

    public void init() {
        syncPanel.init();
        blockGenPanel.init();
        peerMetricsPanel.init();
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

        shutdownExecutor(syncExecutor, "sync");
        shutdownExecutor(blockGenExecutor, "blockGen");
        shutdownExecutor(peerExecutor, "peer");
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
    }
}