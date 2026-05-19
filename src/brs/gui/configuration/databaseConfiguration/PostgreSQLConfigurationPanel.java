package brs.gui.configuration.databaseConfiguration;

import javax.swing.JPanel;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;

public class PostgreSQLConfigurationPanel extends JPanel {

    public PostgreSQLConfigurationPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(new JLabel("PostgreSQL Configuration"));

        JButton configureButton = new JButton("Configure PostgreSQL");
        configureButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(this, "PostgreSQL configuration is not yet implemented.");
        });

        add(configureButton);
    }
}
