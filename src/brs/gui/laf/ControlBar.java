/*
 * Copyright 2019 FormDev Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package brs.gui.laf;

import java.awt.*;
import brs.gui.SignumGUI;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.*;
import javax.swing.UIManager.LookAndFeelInfo;
import javax.swing.plaf.metal.MetalLookAndFeel;
import com.formdev.flatlaf.*;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.LoggingFacade;
import com.formdev.flatlaf.util.SystemInfo;
import com.formdev.flatlaf.util.UIScale;
import net.miginfocom.layout.ConstraintParser;
import net.miginfocom.layout.LC;
import net.miginfocom.layout.UnitValue;
import net.miginfocom.swing.*;

/**
 * @author Karl Tauber
 */
class ControlBar
        extends JPanel {
    private FlatLafPanel frame;
    private JTabbedPane tabbedPane;

    ControlBar() {
        initComponents();

        // remove top insets
        MigLayout layout = (MigLayout) getLayout();
        LC lc = ConstraintParser.parseLayoutConstraint((String) layout.getLayoutConstraints());
        UnitValue[] insets = lc.getInsets();
        lc.setInsets(new UnitValue[] {
                new UnitValue(0, UnitValue.PIXEL, null),
                insets[1],
                insets[2],
                insets[3]
        });
        layout.setLayoutConstraints(lc);

        // initialize look and feels combo box
        DefaultComboBoxModel<LookAndFeelInfo> lafModel = new DefaultComboBoxModel<>();
        lafModel.addElement(new LookAndFeelInfo("FlatLaf Light (F1)", FlatLightLaf.class.getName()));
        lafModel.addElement(new LookAndFeelInfo("FlatLaf Dark (F2)", FlatDarkLaf.class.getName()));
        lafModel.addElement(new LookAndFeelInfo("FlatLaf IntelliJ (F3)", FlatIntelliJLaf.class.getName()));
        lafModel.addElement(new LookAndFeelInfo("FlatLaf Darcula (F4)", FlatDarculaLaf.class.getName()));
        lafModel.addElement(new LookAndFeelInfo("FlatLaf macOS Light (F5)", FlatMacLightLaf.class.getName()));
        lafModel.addElement(new LookAndFeelInfo("FlatLaf macOS Dark (F6)", FlatMacDarkLaf.class.getName()));

        UIManager.LookAndFeelInfo[] lookAndFeels = UIManager.getInstalledLookAndFeels();
        for (UIManager.LookAndFeelInfo lookAndFeel : lookAndFeels) {
            String name = lookAndFeel.getName();
            String className = lookAndFeel.getClassName();
            if (className.equals("com.sun.java.swing.plaf.windows.WindowsClassicLookAndFeel") ||
                    className.equals("com.sun.java.swing.plaf.motif.MotifLookAndFeel") ||
                    className.toLowerCase().contains("nimbus"))
                continue;

            if ((SystemInfo.isWindows && className.equals("com.sun.java.swing.plaf.windows.WindowsLookAndFeel")) ||
                    (SystemInfo.isMacOS && className.equals("com.apple.laf.AquaLookAndFeel")) ||
                    (SystemInfo.isLinux && className.equals("com.sun.java.swing.plaf.gtk.GTKLookAndFeel")))
                name += " (F9)";
            else if (className.equals(MetalLookAndFeel.class.getName()))
                name += " (F12)";

            lafModel.addElement(new LookAndFeelInfo(name, className));
        }

        lookAndFeelComboBox.setModel(lafModel);

        lookAndFeelComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof LookAndFeelInfo) {
                    setText(((LookAndFeelInfo) value).getName());
                }
                return this;
            }
        });

        UIManager.addPropertyChangeListener(e -> {
            if ("lookAndFeel".equals(e.getPropertyName())) {
                EventQueue.invokeLater(() -> {
                    // update info label because user scale factor may change
                    updateInfoLabel();

                    // update "Font" menu
                    frame.updateFontMenuItems();

                    // this is necessary because embedded JOptionPane's "steal" the default button
                    frame.getRootPane().setDefaultButton(closeButton);
                });
            }
        });

        UIScale.addPropertyChangeListener(e -> {
            // update info label because user scale factor may change
            updateInfoLabel();

            // update "Font" menu (e.g. if zoom factor changed)
            if (UIScale.PROP_ZOOM_FACTOR.equals(e.getPropertyName()))
                frame.updateFontMenuItems();
        });
    }

    @Override
    public void updateUI() {
        super.updateUI();

        if (infoLabel != null)
            updateInfoLabel();
    }

    void initialize(FlatLafPanel frame, JTabbedPane tabbedPane) {
        this.frame = frame;
        this.tabbedPane = tabbedPane;

        // register F1, F2, ... keys to switch to Light, Dark or other LaFs
        registerSwitchToLookAndFeel(KeyEvent.VK_F1, FlatLightLaf.class.getName());
        registerSwitchToLookAndFeel(KeyEvent.VK_F2, FlatDarkLaf.class.getName());
        registerSwitchToLookAndFeel(KeyEvent.VK_F3, FlatIntelliJLaf.class.getName());
        registerSwitchToLookAndFeel(KeyEvent.VK_F4, FlatDarculaLaf.class.getName());
        registerSwitchToLookAndFeel(KeyEvent.VK_F5, FlatMacLightLaf.class.getName());
        registerSwitchToLookAndFeel(KeyEvent.VK_F6, FlatMacDarkLaf.class.getName());

        if (SystemInfo.isWindows)
            registerSwitchToLookAndFeel(KeyEvent.VK_F9, "com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        else if (SystemInfo.isMacOS)
            registerSwitchToLookAndFeel(KeyEvent.VK_F9, "com.apple.laf.AquaLookAndFeel");
        else if (SystemInfo.isLinux)
            registerSwitchToLookAndFeel(KeyEvent.VK_F9, "com.sun.java.swing.plaf.gtk.GTKLookAndFeel");
        registerSwitchToLookAndFeel(KeyEvent.VK_F12, MetalLookAndFeel.class.getName());

        // register Alt+UP and Alt+DOWN to switch to previous/next theme
        frame.registerKeyboardAction(
                e -> frame.themesPanel.selectPreviousTheme(),
                KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.ALT_DOWN_MASK),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        frame.registerKeyboardAction(
                e -> frame.themesPanel.selectNextTheme(),
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        // register Alt+Shift+F1, F2, ... keys to change system scale factor
        FlatLafPrefs.registerSystemScaleFactors(frame);

        // register Alt+Shift+S to enable/disable interprocess Laf sync
        FlatLafPrefs.initLafSync(frame);

        // register ESC key to close frame
        frame.registerKeyboardAction(
                e -> {
                    frame.dispose();
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, false),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        // make the "close" button the default button
        if (frame.getRootPane() != null)
            frame.getRootPane().setDefaultButton(closeButton);

        // update info label and move focus to "close" button
        frame.addHierarchyListener(new HierarchyListener() {
            @Override
            public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && frame.isShowing()) {
                    updateInfoLabel();
                    closeButton.requestFocusInWindow();

                    JRootPane rootPane = frame.getRootPane();
                    if (rootPane != null) {
                        rootPane.setDefaultButton(closeButton);
                    }
                }
            }
        });

        // update info label when moved to another screen
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                updateInfoLabel();
            }
        });
    }

    private void updateInfoLabel() {
        String javaVendor = System.getProperty("java.vendor");
        if ("Oracle Corporation".equals(javaVendor))
            javaVendor = null;
        float zoomFactor = UIScale.getZoomFactor();
        double systemScaleFactor = UIScale.getSystemScaleFactor(getGraphicsConfiguration());
        float userScaleFactor = UIScale.getUserScaleFactor();
        Font font = UIManager.getFont("Label.font");
        String newInfo = "(Java " + System.getProperty("java.version")
                + (javaVendor != null ? ("; " + javaVendor) : "")
                + (zoomFactor != 1 ? (";  zoom " + zoomFactor) : "")
                + (systemScaleFactor != 1 ? (";  system scale " + systemScaleFactor) : "")
                + (userScaleFactor != 1 ? (";  user scale " + userScaleFactor) : "")
                + (systemScaleFactor == 1 && userScaleFactor == 1 ? "; no scaling" : "")
                + "; " + font.getFamily() + " " + font.getSize()
                + (font.isBold() ? " BOLD" : "")
                + (font.isItalic() ? " ITALIC" : "")
                + ")";

        if (!newInfo.equals(infoLabel.getText()))
            infoLabel.setText(newInfo);
    }

    private void registerSwitchToLookAndFeel(int keyCode, String lafClassName) {
        frame.registerKeyboardAction(
                e -> {
                    selectLookAndFeel(lafClassName);
                },
                KeyStroke.getKeyStroke(keyCode, 0, false),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    }

    private void selectLookAndFeel(String lafClassName) {
        lookAndFeelComboBox.setSelectedLookAndFeel(lafClassName);
    }

    private void lookAndFeelChanged() {
        String lafClassName = lookAndFeelComboBox.getSelectedLookAndFeel();
        if (lafClassName == null)
            return;

        if (lafClassName.equals(UIManager.getLookAndFeel().getClass().getName()))
            return;

        EventQueue.invokeLater(() -> {
            try {
                FlatAnimatedLafChange.showSnapshot();

                // change look and feel
                UIManager.setLookAndFeel(lafClassName);

                // update all components
                SignumGUI.updateAllUIs();

                // increase size of frame if necessary
                Window window = SwingUtilities.windowForComponent(this);
                if (window != null) {
                    window.pack();
                }

                FlatAnimatedLafChange.hideSnapshotWithAnimation();
            } catch (Exception ex) {
                LoggingFacade.INSTANCE.logSevere(null, ex);
            }
        });
    }

    private void rightToLeftChanged() {
        boolean rightToLeft = rightToLeftCheckBox.isSelected();
        rightToLeftChanged(frame, rightToLeft);
    }

    private void rightToLeftChanged(Container c, boolean rightToLeft) {
        c.applyComponentOrientation(rightToLeft
                ? ComponentOrientation.RIGHT_TO_LEFT
                : ComponentOrientation.LEFT_TO_RIGHT);
        c.revalidate();
        c.repaint();
    }

    private void enabledChanged() {
        enabledDisable(tabbedPane, enabledCheckBox.isSelected());

        // repainting whole tabbed pane is faster than repainting many individual
        // components
        tabbedPane.repaint();
    }

    private void enabledDisable(Container container, boolean enabled) {
        for (Component c : container.getComponents()) {
            if (c instanceof JPanel) {
                enabledDisable((JPanel) c, enabled);
                continue;
            }

            c.setEnabled(enabled);

            if (c instanceof JScrollPane) {
                Component view = ((JScrollPane) c).getViewport().getView();
                if (view != null)
                    view.setEnabled(enabled);
            } else if (c instanceof JTabbedPane) {
                JTabbedPane tabPane = (JTabbedPane) c;
                int tabCount = tabPane.getTabCount();
                for (int i = 0; i < tabCount; i++) {
                    Component tab = tabPane.getComponentAt(i);
                    if (tab != null)
                        tab.setEnabled(enabled);
                }
            }

            if (c instanceof JToolBar)
                enabledDisable((JToolBar) c, enabled);
        }
    }

    private void closePerformed() {
        frame.exitActionPerformed();
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY
        // //GEN-BEGIN:initComponents
        separator1 = new JSeparator();
        lookAndFeelComboBox = new LookAndFeelsComboBox();
        rightToLeftCheckBox = new JCheckBox();
        enabledCheckBox = new JCheckBox();
        infoLabel = new JLabel();
        closeButton = new JButton();

        // ======== this ========
        setLayout(new MigLayout(
                "insets dialog",
                // columns
                "[fill]" +
                        "[fill]" +
                        "[fill]" +
                        "[grow,fill]" +
                        "[button,fill]",
                // rows
                "[bottom]" +
                        "[]"));
        add(separator1, "cell 0 0 5 1");

        // ---- lookAndFeelComboBox ----
        lookAndFeelComboBox.addActionListener(e -> lookAndFeelChanged());
        add(lookAndFeelComboBox, "cell 0 1");

        // ---- rightToLeftCheckBox ----
        rightToLeftCheckBox.setText("right-to-left");
        rightToLeftCheckBox.setMnemonic('R');
        rightToLeftCheckBox.addActionListener(e -> rightToLeftChanged());
        add(rightToLeftCheckBox, "cell 1 1");

        // ---- enabledCheckBox ----
        enabledCheckBox.setText("enabled");
        enabledCheckBox.setMnemonic('E');
        enabledCheckBox.setSelected(true);
        enabledCheckBox.addActionListener(e -> enabledChanged());
        add(enabledCheckBox, "cell 2 1");

        // ---- infoLabel ----
        infoLabel.setText("text");
        add(infoLabel, "cell 3 1,alignx center,growx 0");

        // ---- closeButton ----
        closeButton.setText("Close");
        closeButton.addActionListener(e -> closePerformed());
        add(closeButton, "cell 4 1");
        // JFormDesigner - End of component initialization //GEN-END:initComponents
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY //GEN-BEGIN:variables
    private JSeparator separator1;
    private LookAndFeelsComboBox lookAndFeelComboBox;
    private JCheckBox rightToLeftCheckBox;
    private JCheckBox enabledCheckBox;
    private JLabel infoLabel;
    private JButton closeButton;
    // JFormDesigner - End of variables declaration //GEN-END:variables
}
