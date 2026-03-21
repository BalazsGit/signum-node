package brs.gui;

import brs.gui.util.CustomDrawings;
import brs.gui.util.CustomDrawingComponent;

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
    private final JPanel syncWrapper;
    private final JPanel blockGenWrapper;
    private final JPanel peerWrapper;
    private boolean isExpanded = true;
    private int lastSelectedIndex = 1;
    private final CustomDrawingComponent toggleTab;
    private Timer animationTimer;

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
        peerMetricsPanel = new PeerMetricsPanel(parentFrame, peerExecutor);

        // Create wrappers for collapsing animation
        syncWrapper = new JPanel(new BorderLayout());
        JScrollPane syncScrollPane = new JScrollPane(syncPanel);
        syncScrollPane.setBorder(BorderFactory.createEmptyBorder());
        syncScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        syncWrapper.add(syncScrollPane, BorderLayout.CENTER);

        blockGenWrapper = new JPanel(new BorderLayout());
        JScrollPane blockGenScrollPane = new JScrollPane(blockGenPanel);
        blockGenScrollPane.setBorder(BorderFactory.createEmptyBorder());
        blockGenScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        blockGenWrapper.add(blockGenScrollPane, BorderLayout.CENTER);

        peerWrapper = new JPanel(new BorderLayout());
        JScrollPane peerScrollPane = new JScrollPane(peerMetricsPanel);
        peerScrollPane.setBorder(BorderFactory.createEmptyBorder());
        peerScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        peerWrapper.add(peerScrollPane, BorderLayout.CENTER);

        // Tabs at the bottom
        setTabPlacement(JTabbedPane.BOTTOM);

        // Tab 0: Toggle
        addTab(null, null);
        setToolTipTextAt(0, "Toggle Metrics Panel");
        setEnabledAt(0, false);

        toggleTab = new CustomDrawingComponent(isExpanded ? CustomDrawings.Chevron.UP : CustomDrawings.Chevron.DOWN);
        setTabComponentAt(0, toggleTab);

        // Tab 1: Sync
        addTab("Sync", syncWrapper);

        // Tab 2: Block Gen
        addTab("Block Gen", blockGenWrapper);

        // Tab 3: Peer Metrics
        addTab("Peer Metrics", peerWrapper);

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

        // Calculate max natural height of content
        int h1 = syncPanel.getPreferredSize().height;
        int h2 = blockGenPanel.getPreferredSize().height;
        int h3 = peerMetricsPanel.getPreferredSize().height;
        int naturalHeight = Math.max(h1, Math.max(h2, h3));

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
            startHeight = naturalHeight;
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
    }

    private void updateWrapperHeight(JPanel wrapper, JComponent content, Integer height) {
        if (height == null) {
            wrapper.setPreferredSize(null);
        } else {
            wrapper.setPreferredSize(new Dimension(content.getPreferredSize().width, height));
        }
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