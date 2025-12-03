package brs.gui;

import brs.Block;
import brs.Signum;
import brs.BlockchainProcessor;
import brs.peer.Peer;
import brs.util.Listener;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@SuppressWarnings("serial")
public class PeersDialog extends JFrame {

    private static volatile PeersDialog instance;

    private final Listener<Block> peerListener;
    private final JTabbedPane tabbedPane;

    private enum PeerCategory {
        ACTIVE("Active", p -> p.getState() != Peer.State.NON_CONNECTED),
        CONNECTED("Connected", p -> p.getState() == Peer.State.CONNECTED),
        BLACKLISTED("Blacklisted", Peer::isBlacklisted),
        ALL("All Known", p -> true);

        private final String title;
        private final Predicate<Peer> filter;

        PeerCategory(String title, Predicate<Peer> filter) {
            this.title = title;
            this.filter = filter;
        }
    }

    public static void showPeersDialog(JFrame owner) {
        if (instance == null) {
            synchronized (PeersDialog.class) {
                if (instance == null) {
                    instance = new PeersDialog(owner);
                }
            }
        }
        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
    }

    private PeersDialog(JFrame owner) {
        super("Peer Information");

        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JTextArea legendArea = new JTextArea();
        legendArea.setEditable(false);
        legendArea.setLineWrap(true);
        legendArea.setWrapStyleWord(true);
        legendArea.setBackground(UIManager.getColor("Panel.background"));
        legendArea.setText(
                "Peers: Active / All Known (BL: Blacklisted)\n\n" +
                        "• Active: Peers your node is currently communicating with.\n" +
                        "• Connected: A subset of active peers with a stable connection.\n" +
                        "• Blacklisted: Peers temporarily banned for sending invalid data.\n" +
                        "• All Known: All peers your node has ever discovered.");
        mainPanel.add(legendArea, BorderLayout.NORTH);

        tabbedPane = new JTabbedPane();

        for (PeerCategory category : PeerCategory.values()) {
            tabbedPane.addTab(category.title, createPeerListScrollPane());
        }

        updateTabs(); // Initial population

        peerListener = block -> SwingUtilities.invokeLater(this::updateTabs);
        Signum.getBlockchainProcessor().addListener(peerListener, BlockchainProcessor.Event.PEERS_UPDATED);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                Signum.getBlockchainProcessor().removeListener(peerListener, BlockchainProcessor.Event.PEERS_UPDATED);
                instance = null;
                dispose();
            }
        });

        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        add(mainPanel);
        pack();
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }

    private void updateTabs() {
        Collection<Peer> allPeers = Signum.getBlockchainProcessor().getAllPeers();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            PeerCategory category = PeerCategory.values()[i];
            List<Peer> filteredPeers = allPeers.stream().filter(category.filter).collect(Collectors.toList());
            tabbedPane.setTitleAt(i, category.title + " (" + filteredPeers.size() + ")");
            updatePeerListScrollPane((JScrollPane) tabbedPane.getComponentAt(i), filteredPeers, category);
        }
    }

    private void updatePeerListScrollPane(JScrollPane scrollPane, List<Peer> peers, PeerCategory category) {
        JEditorPane editorPane = (JEditorPane) scrollPane.getViewport().getView();
        peers.sort(Comparator.comparing(Peer::getPeerAddress));
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<html><body style='font-family:monospaced; font-size:12pt;'>");

        if (peers.isEmpty()) {
            sb.append("No peers in this category.");
        } else {
            for (Peer p : peers) {
                String color;
                if (p.isBlacklisted()) {
                    color = "red";
                } else if (p.getState() != Peer.State.NON_CONNECTED) {
                    color = "green";
                } else {
                    color = category == PeerCategory.ALL ? "yellow" : "green";
                }
                sb.append("<font color='").append(color).append("'>");
                String version = p.getVersion().toStringIfNotEmpty();
                if (version.isEmpty()) {
                    version = "unknown";
                }
                sb.append(p.getPeerAddress()).append(" (").append(version).append(")");
                sb.append("</font><br>");
            }
        }
        sb.append("</body></html>");
        editorPane.setText(sb.toString());
        editorPane.setCaretPosition(0);
    }

    private JScrollPane createPeerListScrollPane() {
        JEditorPane editorPane = new JEditorPane();
        editorPane.setContentType("text/html");
        editorPane.setEditable(false);
        editorPane.setBackground(UIManager.getColor("Panel.background"));
        JScrollPane scrollPane = new JScrollPane(editorPane);
        scrollPane.setPreferredSize(new Dimension(400, 250));
        return scrollPane;
    }
}