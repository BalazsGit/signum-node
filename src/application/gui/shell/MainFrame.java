package application.gui.shell;

import javax.swing.JFrame;
import java.awt.BorderLayout;

public class MainFrame extends JFrame {
    private final TabManager tabManager;

    public MainFrame() {
        setTitle("Signum Platform");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        this.tabManager = new TabManager();

        // A TabManager komponensét (a JTabbedPane-t) adjuk a kerethez
        add(tabManager.getComponent(), BorderLayout.CENTER);
    }

    public TabManager getTabManager() {
        return tabManager;
    }
}
