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

import brs.gui.SignumGUI;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import brs.gui.GuiResources;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import brs.gui.laf.HintManager.Hint;
import brs.gui.laf.intellijthemes.IJThemesPanel;
import brs.gui.laf.extras.ExtrasPanel;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.extras.FlatDesktop;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.extras.FlatUIDefaultsInspector;
import com.formdev.flatlaf.extras.components.FlatButton;
import com.formdev.flatlaf.extras.components.FlatButton.ButtonType;
import com.formdev.flatlaf.icons.FlatAbstractIcon;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.extras.FlatSVGUtils;
import com.formdev.flatlaf.util.ColorFunctions;
import com.formdev.flatlaf.util.FontUtils;
import com.formdev.flatlaf.util.LoggingFacade;
import com.formdev.flatlaf.util.SystemFileChooser;
import com.formdev.flatlaf.util.SystemInfo;
import com.formdev.flatlaf.util.UIScale;
import net.miginfocom.layout.ConstraintParser;
import net.miginfocom.layout.LC;
import net.miginfocom.layout.UnitValue;
import net.miginfocom.swing.*;

/**
 * @author Karl Tauber
 */
public class FlatLafPanel
        extends JPanel {
    private final String[] availableFontFamilyNames;
    private int initialFontMenuItemCount = -1;
    private Runnable closeAction;

    public FlatLafPanel() {
        super(new BorderLayout());

        int tabIndex = FlatLafPrefs.getState().getInt(FlatLafCommon.KEY_TAB, 0);

        availableFontFamilyNames = FontUtils.getAvailableFontFamilyNames().clone();
        Arrays.sort(availableFontFamilyNames);

        initComponents();
        initZommMenuItems();
        updateFontMenuItems();
        initAccentColors();
        initFullWindowContent();
        controlBar.initialize(this, tabbedPane);

        // setIconImages(FlatSVGUtils.createWindowIconImages(GuiResources.FLATLAF_RESOURCE_PATH
        // + "FlatLaf.svg"));

        if (tabIndex >= 0 && tabIndex < tabbedPane.getTabCount() && tabIndex != tabbedPane.getSelectedIndex())
            tabbedPane.setSelectedIndex(tabIndex);

        // macOS (see https://www.formdev.com/flatlaf/macos/)
        if (SystemInfo.isMacOS) {
            // hide menu items that are in macOS application menu
            exitMenuItem.setVisible(false);
            aboutMenuItem.setVisible(false);

            // do not use HTML text in menu items because this is not supported in macOS
            // screen menu
            htmlMenuItem.setText("some text");

            // The following macOS-specific properties are for top-level windows
            // and should be set by the container that holds this panel.
            // For example:
            // JRootPane rootPane = getRootPane();
            // rootPane.putClientProperty("apple.awt.fullWindowContent", true);
            // rootPane.putClientProperty("apple.awt.transparentTitleBar", true);
        }

        SwingUtilities.invokeLater(() -> {
            showHints();
        });
    }

    public void setCloseAction(Runnable closeAction) {
        this.closeAction = closeAction;
    }

    public void dispose() {
        FlatUIDefaultsInspector.hide();
    }

    private void showHints() {
        Hint fontMenuHint = new Hint(
                "Use 'Font' menu to increase/decrease font size or try different fonts.",
                fontMenu, SwingConstants.BOTTOM, "hint.fontMenu", null);

        Hint optionsMenuHint = new Hint(
                "Use 'Options' menu to try out various FlatLaf options.",
                optionsMenu, SwingConstants.BOTTOM, "hint.optionsMenu", fontMenuHint);

        Hint themesHint = new Hint(
                "Use 'Themes' list to try out various themes.",
                themesPanel, SwingConstants.LEFT, "hint.themesPanel", optionsMenuHint);

        HintManager.showHint(themesHint);
    }

    private void clearHints() {
        HintManager.hideAllHints();

        Preferences state = FlatLafPrefs.getState();
        state.remove("hint.fontMenu");
        state.remove("hint.optionsMenu");
        state.remove("hint.themesPanel");
    }

    private void showUIDefaultsInspector() {
        FlatUIDefaultsInspector.show();
    }

    private void newActionPerformed() {
        NewDialog newDialog = new NewDialog(SwingUtilities.windowForComponent(this));
        newDialog.setVisible(true);
    }

    private void openActionPerformed() {
        JFileChooser chooser = new JFileChooser();
        chooser.showOpenDialog(this);
    }

    private void saveAsActionPerformed() {
        JFileChooser chooser = new JFileChooser();
        chooser.showSaveDialog(this);
    }

    private void openSystemActionPerformed() {
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.addChoosableFileFilter(new SystemFileChooser.FileNameExtensionFilter(
                "Text Files", "txt", "md"));
        chooser.addChoosableFileFilter(new SystemFileChooser.FileNameExtensionFilter(
                "PDF Files", "pdf"));
        chooser.addChoosableFileFilter(new SystemFileChooser.FileNameExtensionFilter(
                "Archives", "zip", "tar", "jar", "7z"));

        if (chooser.showOpenDialog(this) != SystemFileChooser.APPROVE_OPTION)
            return;

        File[] files = chooser.getSelectedFiles();
        System.out.println(Arrays.toString(files).replace(",", "\n"));
    }

    private void saveAsSystemActionPerformed() {
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.addChoosableFileFilter(new SystemFileChooser.FileNameExtensionFilter(
                "Text Files", "txt", "md"));
        chooser.addChoosableFileFilter(new SystemFileChooser.FileNameExtensionFilter(
                "Images", "png", "gif", "jpg"));

        if (chooser.showSaveDialog(this) != SystemFileChooser.APPROVE_OPTION)
            return;

        File file = chooser.getSelectedFile();
        System.out.println(file);
    }

    private void selectFolderSystemActionPerformed() {
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setFileSelectionMode(SystemFileChooser.DIRECTORIES_ONLY);

        if (chooser.showOpenDialog(this) != SystemFileChooser.APPROVE_OPTION)
            return;

        File directory = chooser.getSelectedFile();
        System.out.println(directory);
    }

    public void exitActionPerformed() {
        dispose();
        if (closeAction != null) {
            closeAction.run();
        }
    }

    private void aboutActionPerformed() {
        JLabel titleLabel = new JLabel("FlatLaf Preview");
        titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h1");

        String link = "https://www.formdev.com/flatlaf/";
        JLabel linkLabel = new JLabel("<html><a href=\"#\">" + link + "</a></html>");
        linkLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        linkLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new URI(link));
                } catch (IOException | URISyntaxException ex) {
                    JOptionPane.showMessageDialog(linkLabel,
                            "Failed to open '" + link + "' in browser.",
                            "About", JOptionPane.PLAIN_MESSAGE);
                }
            }
        });

        JOptionPane.showMessageDialog(this,
                new Object[] {
                        titleLabel,
                        "Previews FlatLaf Swing look and feel",
                        " ",
                        "Copyright 2019-" + Year.now() + " FormDev Software GmbH",
                        linkLabel,
                },
                "About", JOptionPane.PLAIN_MESSAGE);
    }

    private void showPreferences() {
        JOptionPane.showMessageDialog(this,
                "Sorry, but FlatLaf Preview does not have preferences. :(\n"
                        + "This dialog is here to demonstrate usage of class 'FlatDesktop' on macOS.",
                "Preferences", JOptionPane.PLAIN_MESSAGE);
    }

    private void selectedTabChanged() {
        FlatLafPrefs.getState().putInt(FlatLafCommon.KEY_TAB, tabbedPane.getSelectedIndex());
    }

    private void menuItemActionPerformed(ActionEvent e) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, e.getActionCommand(), "Menu Item", JOptionPane.PLAIN_MESSAGE);
        });
    }

    private void windowDecorationsChanged() {
        boolean windowDecorations = windowDecorationsCheckBoxMenuItem.isSelected();

        if (SystemInfo.isLinux) { // enable/disable custom window decorations
            JFrame.setDefaultLookAndFeelDecorated(windowDecorations);
            JDialog.setDefaultLookAndFeelDecorated(windowDecorations);
        } else { // change window decoration of all frames and dialogs
            FlatLaf.setUseNativeWindowDecorations(windowDecorations);
        }

        menuBarEmbeddedCheckBoxMenuItem.setEnabled(windowDecorations);
        unifiedTitleBarMenuItem.setEnabled(windowDecorations);
        showTitleBarIconMenuItem.setEnabled(windowDecorations);
        JOptionPane.showMessageDialog(this,
                "A program újraindítása szükséges a beállítások teljes körű alkalmazásához.",
                "Újraindítás szükséges", JOptionPane.INFORMATION_MESSAGE);
    }

    private void menuBarEmbeddedChanged() {
        UIManager.put("TitlePane.menuBarEmbedded", menuBarEmbeddedCheckBoxMenuItem.isSelected());
        FlatLaf.revalidateAndRepaintAllFramesAndDialogs();
    }

    private void unifiedTitleBar() {
        UIManager.put("TitlePane.unifiedBackground", unifiedTitleBarMenuItem.isSelected());
        FlatLaf.repaintAllFramesAndDialogs();
    }

    private void showTitleBarIcon() {
        boolean showIcon = showTitleBarIconMenuItem.isSelected();

        // for main frame (because already created)
        JRootPane rootPane = getRootPane();
        if (rootPane != null)
            rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, showIcon);

        // for other not yet created frames/dialogs
        UIManager.put("TitlePane.showIcon", showIcon);
    }

    private void underlineMenuSelection() {
        UIManager.put("MenuItem.selectionType", underlineMenuSelectionMenuItem.isSelected() ? "underline" : null);
    }

    private void alwaysShowMnemonics() {
        UIManager.put("Component.hideMnemonics", !alwaysShowMnemonicsMenuItem.isSelected());
        repaint();
    }

    private void animatedLafChangeChanged() {
        System.setProperty("flatlaf.animatedLafChange", String.valueOf(animatedLafChangeMenuItem.isSelected()));
    }

    private void showHintsChanged() {
        clearHints();
        showHints();
    }

    private void initZommMenuItems() {
        float currentZoomFactor = UIScale.getZoomFactor();
        UIScale.setSupportedZoomFactors(new float[] { 0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.75f, 2f });

        ButtonGroup group = new ButtonGroup();
        HashMap<Float, JCheckBoxMenuItem> items = new HashMap<>();

        // add supported zoom factors to "Zoom" menu
        zoomMenu.addSeparator();
        for (float zoomFactor : UIScale.getSupportedZoomFactors()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem((int) (zoomFactor * 100) + "%");
            item.setSelected(zoomFactor == currentZoomFactor);
            item.addActionListener(this::zoomFactorChanged);
            zoomMenu.add(item);

            group.add(item);
            items.put(zoomFactor, item);
        }

        // update menu item selection if zoom factor changed
        UIScale.addPropertyChangeListener(e -> {
            if (UIScale.PROP_ZOOM_FACTOR.equals(e.getPropertyName())) {
                float newZoomFactor = UIScale.getZoomFactor();
                JCheckBoxMenuItem item = items.get(newZoomFactor);
                if (item != null)
                    item.setSelected(true);
            }
        });
    }

    private static void zoomWindowBounds(Window window, float oldZoomFactor, float newZoomFactor) {
        if (window instanceof Frame && ((Frame) window).getExtendedState() != Frame.NORMAL)
            return;

        Rectangle oldBounds = window.getBounds();

        // zoom window bounds
        float factor = (1f / oldZoomFactor) * newZoomFactor;
        int newWidth = (int) (oldBounds.width * factor);
        int newHeight = (int) (oldBounds.height * factor);
        int newX = oldBounds.x - ((newWidth - oldBounds.width) / 2);
        int newY = oldBounds.y - ((newHeight - oldBounds.height) / 2);

        // get maximum window bounds (screen bounds minus screen insets)
        GraphicsConfiguration gc = window.getGraphicsConfiguration();
        Rectangle screenBounds = gc.getBounds();
        Insets screenInsets = FlatUIUtils.getScreenInsets(gc);
        Rectangle maxBounds = FlatUIUtils.subtractInsets(screenBounds, screenInsets);

        // limit new window width/height
        newWidth = Math.min(newWidth, maxBounds.width);
        newHeight = Math.min(newHeight, maxBounds.height);

        // move window into screen bounds
        newX = Math.max(Math.min(newX, maxBounds.width - newWidth), maxBounds.x);
        newY = Math.max(Math.min(newY, maxBounds.height - newHeight), maxBounds.y);

        // set new window bounds
        window.setBounds(newX, newY, newWidth, newHeight);
    }

    private void zoomFactorChanged(ActionEvent e) {
        String zoomFactor = e.getActionCommand();
        float zoom = Integer.parseInt(zoomFactor.substring(0, zoomFactor.length() - 1)) / 100f;

        if (UIScale.setZoomFactor(zoom))
            FlatLaf.updateUI();
    }

    private void zoomReset() {
        if (UIScale.zoomReset())
            FlatLaf.updateUI();
    }

    private void zoomIn() {
        if (UIScale.zoomIn())
            FlatLaf.updateUI();
    }

    private void zoomOut() {
        if (UIScale.zoomOut())
            FlatLaf.updateUI();
    }

    private void fontFamilyChanged(ActionEvent e) {
        String fontFamily = e.getActionCommand();

        FlatAnimatedLafChange.showSnapshot();

        Font font = UIManager.getFont("defaultFont");
        Font newFont = FontUtils.getCompositeFont(fontFamily, font.getStyle(), font.getSize());
        UIManager.put("defaultFont", newFont);

        SignumGUI.updateAllUIs();
        FlatAnimatedLafChange.hideSnapshotWithAnimation();
    }

    private void fontSizeChanged(ActionEvent e) {
        String fontSizeStr = e.getActionCommand();

        Font font = UIManager.getFont("defaultFont");
        Font newFont = font.deriveFont((float) Integer.parseInt(fontSizeStr));
        UIManager.put("defaultFont", newFont);

        SignumGUI.updateAllUIs();
    }

    private void restoreFont() {
        UIManager.put("defaultFont", null);
        updateFontMenuItems();
        SignumGUI.updateAllUIs();
    }

    private void incrFont() {
        Font font = UIManager.getFont("defaultFont");
        Font newFont = font.deriveFont((float) (font.getSize() + 1));
        UIManager.put("defaultFont", newFont);

        updateFontMenuItems();
        SignumGUI.updateAllUIs();
    }

    private void decrFont() {
        Font font = UIManager.getFont("defaultFont");
        Font newFont = font.deriveFont((float) Math.max(font.getSize() - 1, 10));
        UIManager.put("defaultFont", newFont);

        updateFontMenuItems();
        SignumGUI.updateAllUIs();
    }

    void updateFontMenuItems() {
        if (initialFontMenuItemCount < 0)
            initialFontMenuItemCount = fontMenu.getItemCount();
        else {
            // remove old font items
            for (int i = fontMenu.getItemCount() - 1; i >= initialFontMenuItemCount; i--)
                fontMenu.remove(i);
        }

        // get current font
        Font currentFont = UIManager.getFont("Label.font");
        String currentFamily = currentFont.getFamily();
        String currentSize = Integer.toString(currentFont.getSize());

        // add font families
        fontMenu.addSeparator();
        ArrayList<String> families = new ArrayList<>(Arrays.asList(
                "Arial", "Cantarell", "Comic Sans MS", "DejaVu Sans",
                "Dialog", "Inter", "Liberation Sans", "Noto Sans", "Open Sans", "Roboto",
                "SansSerif", "Segoe UI", "Serif", "Tahoma", "Ubuntu", "Verdana"));
        if (!families.contains(currentFamily))
            families.add(currentFamily);
        families.sort(String.CASE_INSENSITIVE_ORDER);

        ButtonGroup familiesGroup = new ButtonGroup();
        for (String family : families) {
            if (Arrays.binarySearch(availableFontFamilyNames, family) < 0)
                continue; // not available

            JCheckBoxMenuItem item = new JCheckBoxMenuItem(family);
            item.setSelected(family.equals(currentFamily));
            item.addActionListener(this::fontFamilyChanged);
            fontMenu.add(item);

            familiesGroup.add(item);
        }

        // add font sizes
        fontMenu.addSeparator();
        ArrayList<String> sizes = new ArrayList<>(Arrays.asList(
                "10", "11", "12", "14", "16", "18", "20", "24", "28"));
        if (!sizes.contains(currentSize))
            sizes.add(currentSize);
        sizes.sort(String.CASE_INSENSITIVE_ORDER);

        ButtonGroup sizesGroup = new ButtonGroup();
        for (String size : sizes) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(size);
            item.setSelected(size.equals(currentSize));
            item.addActionListener(this::fontSizeChanged);
            fontMenu.add(item);

            sizesGroup.add(item);
        }

        // enabled/disable items
        boolean enabled = UIManager.getLookAndFeel() instanceof FlatLaf;
        for (Component item : fontMenu.getMenuComponents())
            item.setEnabled(enabled);
    }

    // the real colors are defined in
    // flatlaf-demo/src/main/resources/com/formdev/flatlaf/demo/FlatLightLaf.properties
    // and
    // flatlaf-demo/src/main/resources/com/formdev/flatlaf/demo/FlatDarkLaf.properties
    private static String[] accentColorKeys = {
            "Demo.accent.default", "Demo.accent.blue", "Demo.accent.purple", "Demo.accent.red",
            "Demo.accent.orange", "Demo.accent.yellow", "Demo.accent.green",
    };
    private static String[] accentColorNames = {
            "Default", "Blue", "Purple", "Red", "Orange", "Yellow", "Green",
    };
    private final JToggleButton[] accentColorButtons = new JToggleButton[accentColorKeys.length];
    private JLabel accentColorLabel;
    private Color accentColor;

    private void initAccentColors() {
        accentColorLabel = new JLabel("Accent color: ");

        toolBar.add(Box.createHorizontalGlue());
        toolBar.add(accentColorLabel);

        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < accentColorButtons.length; i++) {
            accentColorButtons[i] = new JToggleButton(new AccentColorIcon(accentColorKeys[i]));
            accentColorButtons[i].setToolTipText(accentColorNames[i]);
            accentColorButtons[i].addActionListener(this::accentColorChanged);
            toolBar.add(accentColorButtons[i]);
            group.add(accentColorButtons[i]);
        }

        accentColorButtons[0].setSelected(true);

        FlatLaf.setSystemColorGetter(name -> {
            return name.equals("accent") ? accentColor : null;
        });

        UIManager.addPropertyChangeListener(e -> {
            if ("lookAndFeel".equals(e.getPropertyName()))
                updateAccentColorButtons();
        });
        updateAccentColorButtons();
    }

    private void accentColorChanged(ActionEvent e) {
        String accentColorKey = null;
        for (int i = 0; i < accentColorButtons.length; i++) {
            if (accentColorButtons[i].isSelected()) {
                accentColorKey = accentColorKeys[i];
                break;
            }
        }

        accentColor = (accentColorKey != null && accentColorKey != accentColorKeys[0])
                ? UIManager.getColor(accentColorKey)
                : null;

        Class<? extends LookAndFeel> lafClass = UIManager.getLookAndFeel().getClass();
        try {
            FlatLaf.setup(lafClass.getDeclaredConstructor().newInstance());
            FlatLaf.updateUI();
        } catch (Exception ex) {
            LoggingFacade.INSTANCE.logSevere(null, ex);
        }
    }

    private void updateAccentColorButtons() {
        Class<? extends LookAndFeel> lafClass = UIManager.getLookAndFeel().getClass();
        boolean isAccentColorSupported = lafClass == FlatLightLaf.class ||
                lafClass == FlatDarkLaf.class ||
                lafClass == FlatIntelliJLaf.class ||
                lafClass == FlatDarculaLaf.class ||
                lafClass == FlatMacLightLaf.class ||
                lafClass == FlatMacDarkLaf.class;

        accentColorLabel.setVisible(isAccentColorSupported);
        for (int i = 0; i < accentColorButtons.length; i++)
            accentColorButtons[i].setVisible(isAccentColorSupported);
    }

    private void initFullWindowContent() {
        if (!supportsFlatLafWindowDecorations())
            return;

        // create fullWindowContent mode toggle button
        Icon expandIcon = new FlatSVGIcon(GuiResources.FLATLAF_RESOURCE_PATH + "icons/expand.svg",
                getClass().getClassLoader());
        Icon collapseIcon = new FlatSVGIcon(GuiResources.FLATLAF_RESOURCE_PATH + "icons/collapse.svg",
                getClass().getClassLoader());
        JToggleButton fullWindowContentButton = new JToggleButton(expandIcon);
        fullWindowContentButton.setToolTipText("Toggle full window content");
        fullWindowContentButton.addActionListener(e -> {
            boolean fullWindowContent = fullWindowContentButton.isSelected();
            fullWindowContentButton.setIcon(fullWindowContent ? collapseIcon : expandIcon);
            menuBar.setVisible(!fullWindowContent);
            toolBar.setVisible(!fullWindowContent);
            JRootPane rootPane = getRootPane();
            if (rootPane != null)
                rootPane.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT, fullWindowContent);
        });

        // add fullWindowContent mode toggle button to tabbed pane
        JToolBar trailingToolBar = new JToolBar();
        trailingToolBar.add(Box.createGlue());
        trailingToolBar.add(fullWindowContentButton);
        tabbedPane.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, trailingToolBar);
    }

    private boolean supportsFlatLafWindowDecorations() {
        return FlatLaf.supportsNativeWindowDecorations() || SystemInfo.isLinux;
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY
        // //GEN-BEGIN:initComponents
        menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu();
        JMenuItem newMenuItem = new JMenuItem();
        JMenuItem openMenuItem = new JMenuItem();
        JMenuItem saveAsMenuItem = new JMenuItem();
        JMenuItem openSystemMenuItem = new JMenuItem();
        JMenuItem saveAsSystemMenuItem = new JMenuItem();
        JMenuItem selectFolderSystemMenuItem = new JMenuItem();
        JMenuItem closeMenuItem = new JMenuItem();
        exitMenuItem = new JMenuItem();
        JMenu editMenu = new JMenu();
        JMenuItem undoMenuItem = new JMenuItem();
        JMenuItem redoMenuItem = new JMenuItem();
        JMenuItem cutMenuItem = new JMenuItem();
        JMenuItem copyMenuItem = new JMenuItem();
        JMenuItem pasteMenuItem = new JMenuItem();
        JMenuItem deleteMenuItem = new JMenuItem();
        JMenu viewMenu = new JMenu();
        JCheckBoxMenuItem checkBoxMenuItem1 = new JCheckBoxMenuItem();
        JMenu menu1 = new JMenu();
        JMenu subViewsMenu = new JMenu();
        JMenu subSubViewsMenu = new JMenu();
        JMenuItem errorLogViewMenuItem = new JMenuItem();
        JMenuItem searchViewMenuItem = new JMenuItem();
        JMenuItem projectViewMenuItem = new JMenuItem();
        JMenuItem structureViewMenuItem = new JMenuItem();
        JMenuItem propertiesViewMenuItem = new JMenuItem();
        scrollingPopupMenu = new JMenu();
        JMenuItem menuItem2 = new JMenuItem();
        htmlMenuItem = new JMenuItem();
        JRadioButtonMenuItem radioButtonMenuItem1 = new JRadioButtonMenuItem();
        JRadioButtonMenuItem radioButtonMenuItem2 = new JRadioButtonMenuItem();
        JRadioButtonMenuItem radioButtonMenuItem3 = new JRadioButtonMenuItem();
        zoomMenu = new JMenu();
        JMenuItem resetZoomMenuItem = new JMenuItem();
        JMenuItem incrZoomMenuItem = new JMenuItem();
        JMenuItem decrZoomMenuItem = new JMenuItem();
        fontMenu = new JMenu();
        JMenuItem restoreFontMenuItem = new JMenuItem();
        JMenuItem incrFontMenuItem = new JMenuItem();
        JMenuItem decrFontMenuItem = new JMenuItem();
        optionsMenu = new JMenu();
        windowDecorationsCheckBoxMenuItem = new JCheckBoxMenuItem();
        menuBarEmbeddedCheckBoxMenuItem = new JCheckBoxMenuItem();
        unifiedTitleBarMenuItem = new JCheckBoxMenuItem();
        showTitleBarIconMenuItem = new JCheckBoxMenuItem();
        underlineMenuSelectionMenuItem = new JCheckBoxMenuItem();
        alwaysShowMnemonicsMenuItem = new JCheckBoxMenuItem();
        animatedLafChangeMenuItem = new JCheckBoxMenuItem();
        JMenuItem showHintsMenuItem = new JMenuItem();
        JMenuItem showUIDefaultsInspectorMenuItem = new JMenuItem();
        JMenu helpMenu = new JMenu();
        aboutMenuItem = new JMenuItem();
        JPanel toolBarPanel = new JPanel();
        JPanel macFullWindowContentButtonsPlaceholder = new JPanel();
        toolBar = new JToolBar();
        JButton backButton = new JButton();
        JButton forwardButton = new JButton();
        JButton cutButton = new JButton();
        JButton copyButton = new JButton();
        JButton pasteButton = new JButton();
        JButton refreshButton = new JButton();
        JToggleButton showToggleButton = new JToggleButton();
        JPanel contentPanel = new JPanel();
        tabbedPane = new JTabbedPane();
        BasicComponentsPanel basicComponentsPanel = new BasicComponentsPanel();
        MoreComponentsPanel moreComponentsPanel = new MoreComponentsPanel();
        DataComponentsPanel dataComponentsPanel = new DataComponentsPanel();
        TabsPanel tabsPanel = new TabsPanel();
        OptionPanePanel optionPanePanel = new OptionPanePanel();
        ExtrasPanel extrasPanel = new ExtrasPanel();
        controlBar = new ControlBar();
        JPanel themesPanelPanel = new JPanel();
        JPanel winFullWindowContentButtonsPlaceholder = new JPanel();
        themesPanel = new IJThemesPanel();

        // ======== this ========
        // setTitle("FlatLaf Preview");
        // setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        // ======== menuBar ========
        {

            // ======== fileMenu ========
            {
                fileMenu.setText("File");
                fileMenu.setMnemonic('F');

                // ---- newMenuItem ----
                newMenuItem.setText("New");
                newMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_N, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                newMenuItem.setMnemonic('N');
                newMenuItem.addActionListener(e -> newActionPerformed());
                fileMenu.add(newMenuItem);

                // ---- openMenuItem ----
                openMenuItem.setText("Open...");
                openMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_O, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                openMenuItem.setMnemonic('O');
                openMenuItem.addActionListener(e -> openActionPerformed());
                fileMenu.add(openMenuItem);

                // ---- saveAsMenuItem ----
                saveAsMenuItem.setText("Save As...");
                saveAsMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                saveAsMenuItem.setMnemonic('S');
                saveAsMenuItem.addActionListener(e -> saveAsActionPerformed());
                fileMenu.add(saveAsMenuItem);
                fileMenu.addSeparator();

                // ---- openSystemMenuItem ----
                openSystemMenuItem.setText("Open (System)...");
                openSystemMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.SHIFT_DOWN_MASK));
                openSystemMenuItem.addActionListener(e -> openSystemActionPerformed());
                fileMenu.add(openSystemMenuItem);

                // ---- saveAsSystemMenuItem ----
                saveAsSystemMenuItem.setText("Save As (System)...");
                saveAsSystemMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.SHIFT_DOWN_MASK));
                saveAsSystemMenuItem.addActionListener(e -> saveAsSystemActionPerformed());
                fileMenu.add(saveAsSystemMenuItem);

                // ---- selectFolderSystemMenuItem ----
                selectFolderSystemMenuItem.setText("Select Folder (System)...");
                selectFolderSystemMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.SHIFT_DOWN_MASK));
                selectFolderSystemMenuItem.addActionListener(e -> selectFolderSystemActionPerformed());
                fileMenu.add(selectFolderSystemMenuItem);
                fileMenu.addSeparator();

                // ---- closeMenuItem ----
                closeMenuItem.setText("Close");
                closeMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_W, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                closeMenuItem.setMnemonic('C');
                closeMenuItem.addActionListener(e -> menuItemActionPerformed(e));
                fileMenu.add(closeMenuItem);
                fileMenu.addSeparator();

                // ---- exitMenuItem ----
                exitMenuItem.setText("Exit");
                exitMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_Q, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                exitMenuItem.setMnemonic('X');
                exitMenuItem.addActionListener(e -> exitActionPerformed());
                fileMenu.add(exitMenuItem);
            }
            menuBar.add(fileMenu);

            // ======== editMenu ========
            {
                editMenu.setText("Edit");
                editMenu.setMnemonic('E');

                // ---- undoMenuItem ----
                undoMenuItem.setText("Undo");
                undoMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                undoMenuItem.setMnemonic('U');
                undoMenuItem.setIcon(
                        new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "undo.svg", getClass().getClassLoader()));
                undoMenuItem.addActionListener(e -> menuItemActionPerformed(e));
                editMenu.add(undoMenuItem);

                // ---- redoMenuItem ----
                redoMenuItem.setText("Redo");
                redoMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                redoMenuItem.setMnemonic('R');
                redoMenuItem.setIcon(
                        new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "redo.svg", getClass().getClassLoader()));
                redoMenuItem.addActionListener(e -> menuItemActionPerformed(e));
                editMenu.add(redoMenuItem);
                editMenu.addSeparator();

                // ---- cutMenuItem ----
                cutMenuItem.setText("Cut");
                cutMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_X, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                cutMenuItem.setMnemonic('C');
                cutMenuItem.setIcon(
                        new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "menu-cut.svg", getClass().getClassLoader()));
                editMenu.add(cutMenuItem);

                // ---- copyMenuItem ----
                copyMenuItem.setText("Copy");
                copyMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                copyMenuItem.setMnemonic('O');
                copyMenuItem.setIcon(
                        new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "copy.svg", getClass().getClassLoader()));
                editMenu.add(copyMenuItem);

                // ---- pasteMenuItem ----
                pasteMenuItem.setText("Paste");
                pasteMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                pasteMenuItem.setMnemonic('P');
                pasteMenuItem.setIcon(new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "menu-paste.svg",
                        getClass().getClassLoader()));
                editMenu.add(pasteMenuItem);
                editMenu.addSeparator();

                // ---- deleteMenuItem ----
                deleteMenuItem.setText("Delete");
                deleteMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
                deleteMenuItem.setMnemonic('D');
                deleteMenuItem.addActionListener(e -> menuItemActionPerformed(e));
                editMenu.add(deleteMenuItem);
            }
            menuBar.add(editMenu);

            // ======== viewMenu ========
            {
                viewMenu.setText("View");
                viewMenu.setMnemonic('V');

                // ---- checkBoxMenuItem1 ----
                checkBoxMenuItem1.setText("Show Toolbar");
                checkBoxMenuItem1.setSelected(true);
                checkBoxMenuItem1.setMnemonic('T');
                checkBoxMenuItem1.addActionListener(e -> menuItemActionPerformed(e));
                viewMenu.add(checkBoxMenuItem1);

                // ======== menu1 ========
                {
                    menu1.setText("Show View");
                    menu1.setMnemonic('V');

                    // ======== subViewsMenu ========
                    {
                        subViewsMenu.setText("Sub Views");
                        subViewsMenu.setMnemonic('S');

                        // ======== subSubViewsMenu ========
                        {
                            subSubViewsMenu.setText("Sub sub Views");
                            subSubViewsMenu.setMnemonic('U');

                            // ---- errorLogViewMenuItem ----
                            errorLogViewMenuItem.setText("Error Log");
                            errorLogViewMenuItem.setMnemonic('E');
                            errorLogViewMenuItem.addActionListener(e -> menuItemActionPerformed(e));
                            subSubViewsMenu.add(errorLogViewMenuItem);
                        }
                        subViewsMenu.add(subSubViewsMenu);

                        // ---- searchViewMenuItem ----
                        searchViewMenuItem.setText("Search");
                        searchViewMenuItem.setMnemonic('S');
                        searchViewMenuItem.addActionListener(e -> menuItemActionPerformed(e));
                        subViewsMenu.add(searchViewMenuItem);
                    }
                    menu1.add(subViewsMenu);

                    // ---- projectViewMenuItem ----
                    projectViewMenuItem.setText("Project");
                    projectViewMenuItem.setMnemonic('P');
                    projectViewMenuItem.addActionListener(e -> menuItemActionPerformed(e));
                    menu1.add(projectViewMenuItem);

                    // ---- structureViewMenuItem ----
                    structureViewMenuItem.setText("Structure");
                    structureViewMenuItem.setMnemonic('T');
                    structureViewMenuItem.addActionListener(e -> menuItemActionPerformed(e));
                    menu1.add(structureViewMenuItem);

                    // ---- propertiesViewMenuItem ----
                    propertiesViewMenuItem.setText("Properties");
                    propertiesViewMenuItem.setMnemonic('O');
                    propertiesViewMenuItem.addActionListener(e -> menuItemActionPerformed(e));
                    menu1.add(propertiesViewMenuItem);
                }
                viewMenu.add(menu1);

                // ======== scrollingPopupMenu ========
                {
                    scrollingPopupMenu.setText("Scrolling Popup Menu");
                }
                viewMenu.add(scrollingPopupMenu);

                // ---- menuItem2 ----
                menuItem2.setText("Disabled Item");
                menuItem2.setEnabled(false);
                viewMenu.add(menuItem2);

                // ---- htmlMenuItem ----
                htmlMenuItem.setText("<html>some <b color=\"red\">HTML</b> <i color=\"blue\">text</i></html>");
                viewMenu.add(htmlMenuItem);
                viewMenu.addSeparator();

                // ---- radioButtonMenuItem1 ----
                radioButtonMenuItem1.setText("Details");
                radioButtonMenuItem1.setSelected(true);
                radioButtonMenuItem1.setMnemonic('D');
                radioButtonMenuItem1.addActionListener(e -> menuItemActionPerformed(e));
                viewMenu.add(radioButtonMenuItem1);

                // ---- radioButtonMenuItem2 ----
                radioButtonMenuItem2.setText("Small Icons");
                radioButtonMenuItem2.setMnemonic('S');
                radioButtonMenuItem2.addActionListener(e -> menuItemActionPerformed(e));
                viewMenu.add(radioButtonMenuItem2);

                // ---- radioButtonMenuItem3 ----
                radioButtonMenuItem3.setText("Large Icons");
                radioButtonMenuItem3.setMnemonic('L');
                radioButtonMenuItem3.addActionListener(e -> menuItemActionPerformed(e));
                viewMenu.add(radioButtonMenuItem3);
            }
            menuBar.add(viewMenu);

            // ======== zoomMenu ========
            {
                zoomMenu.setText("Zoom");

                // ---- resetZoomMenuItem ----
                resetZoomMenuItem.setText("Reset Zoom");
                resetZoomMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_0, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                resetZoomMenuItem.addActionListener(e -> zoomReset());
                zoomMenu.add(resetZoomMenuItem);

                // ---- incrZoomMenuItem ----
                incrZoomMenuItem.setText("Zoom In");
                incrZoomMenuItem.setAccelerator(
                        KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                incrZoomMenuItem.addActionListener(e -> zoomIn());
                zoomMenu.add(incrZoomMenuItem);

                // ---- decrZoomMenuItem ----
                decrZoomMenuItem.setText("Zoom Out");
                decrZoomMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
                decrZoomMenuItem.addActionListener(e -> zoomOut());
                zoomMenu.add(decrZoomMenuItem);
            }
            menuBar.add(zoomMenu);

            // ======== fontMenu ========
            {
                fontMenu.setText("Font");

                // ---- restoreFontMenuItem ----
                restoreFontMenuItem.setText("Restore Font");
                restoreFontMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.ALT_DOWN_MASK));
                restoreFontMenuItem.addActionListener(e -> restoreFont());
                fontMenu.add(restoreFontMenuItem);

                // ---- incrFontMenuItem ----
                incrFontMenuItem.setText("Increase Font Size");
                incrFontMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.ALT_DOWN_MASK));
                incrFontMenuItem.addActionListener(e -> incrFont());
                fontMenu.add(incrFontMenuItem);

                // ---- decrFontMenuItem ----
                decrFontMenuItem.setText("Decrease Font Size");
                decrFontMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.ALT_DOWN_MASK));
                decrFontMenuItem.addActionListener(e -> decrFont());
                fontMenu.add(decrFontMenuItem);
            }
            menuBar.add(fontMenu);

            // ======== optionsMenu ========
            {
                optionsMenu.setText("Options");

                // ---- windowDecorationsCheckBoxMenuItem ----
                windowDecorationsCheckBoxMenuItem.setText("Window decorations");
                windowDecorationsCheckBoxMenuItem.addActionListener(e -> windowDecorationsChanged());
                optionsMenu.add(windowDecorationsCheckBoxMenuItem);

                // ---- menuBarEmbeddedCheckBoxMenuItem ----
                menuBarEmbeddedCheckBoxMenuItem.setText("Embedded menu bar");
                menuBarEmbeddedCheckBoxMenuItem.addActionListener(e -> menuBarEmbeddedChanged());
                optionsMenu.add(menuBarEmbeddedCheckBoxMenuItem);

                // ---- unifiedTitleBarMenuItem ----
                unifiedTitleBarMenuItem.setText("Unified window title bar");
                unifiedTitleBarMenuItem.addActionListener(e -> unifiedTitleBar());
                optionsMenu.add(unifiedTitleBarMenuItem);

                // ---- showTitleBarIconMenuItem ----
                showTitleBarIconMenuItem.setText("Show window title bar icon");
                showTitleBarIconMenuItem.addActionListener(e -> showTitleBarIcon());
                optionsMenu.add(showTitleBarIconMenuItem);

                // ---- underlineMenuSelectionMenuItem ----
                underlineMenuSelectionMenuItem.setText("Use underline menu selection");
                underlineMenuSelectionMenuItem.addActionListener(e -> underlineMenuSelection());
                optionsMenu.add(underlineMenuSelectionMenuItem);

                // ---- alwaysShowMnemonicsMenuItem ----
                alwaysShowMnemonicsMenuItem.setText("Always show mnemonics");
                alwaysShowMnemonicsMenuItem.addActionListener(e -> alwaysShowMnemonics());
                optionsMenu.add(alwaysShowMnemonicsMenuItem);

                // ---- animatedLafChangeMenuItem ----
                animatedLafChangeMenuItem.setText("Animated Laf Change");
                animatedLafChangeMenuItem.setSelected(true);
                animatedLafChangeMenuItem.addActionListener(e -> animatedLafChangeChanged());
                optionsMenu.add(animatedLafChangeMenuItem);

                // ---- showHintsMenuItem ----
                showHintsMenuItem.setText("Show hints");
                showHintsMenuItem.addActionListener(e -> showHintsChanged());
                optionsMenu.add(showHintsMenuItem);

                // ---- showUIDefaultsInspectorMenuItem ----
                showUIDefaultsInspectorMenuItem.setText("Show UI Defaults Inspector");
                showUIDefaultsInspectorMenuItem.addActionListener(e -> showUIDefaultsInspector());
                optionsMenu.add(showUIDefaultsInspectorMenuItem);
            }
            menuBar.add(optionsMenu);

            // ======== helpMenu ========
            {
                helpMenu.setText("Help");
                helpMenu.setMnemonic('H');

                // ---- aboutMenuItem ----
                aboutMenuItem.setText("About");
                aboutMenuItem.setMnemonic('A');
                aboutMenuItem.addActionListener(e -> aboutActionPerformed());
                helpMenu.add(aboutMenuItem);
            }
            menuBar.add(helpMenu);

            // add(menuBar, BorderLayout.NORTH);
        }

        // ======== toolBarPanel ========
        {
            toolBarPanel.setLayout(new BorderLayout());
            toolBarPanel.add(menuBar, BorderLayout.NORTH);

            // ======== macFullWindowContentButtonsPlaceholder ========
            {
                macFullWindowContentButtonsPlaceholder.setLayout(new FlowLayout());
            }
            toolBarPanel.add(macFullWindowContentButtonsPlaceholder, BorderLayout.WEST);

            // ======== toolBar ========
            {
                toolBar.setMargin(new Insets(3, 3, 3, 3));

                // ---- backButton ----
                backButton.setToolTipText(
                        "Back");
                backButton.setIcon(
                        new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "back.svg", getClass().getClassLoader()));
                toolBar.add(backButton);

                // ---- forwardButton ----
                forwardButton.setToolTipText("Forward");
                forwardButton.setIcon(
                        new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "forward.svg", getClass().getClassLoader()));
                toolBar.add(forwardButton);
                toolBar.addSeparator();

                // ---- cutButton ----
                cutButton.setToolTipText("Cut");
                cutButton.setIcon(
                        new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "menu-cut.svg", getClass().getClassLoader()));
                toolBar.add(cutButton);

                // ---- copyButton ----
                copyButton.setToolTipText("Copy");
                copyButton.setIcon(
                        new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "copy.svg", getClass().getClassLoader()));
                toolBar.add(copyButton);

                // ---- pasteButton ----
                pasteButton.setToolTipText("Paste");
                pasteButton.setIcon(new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "menu-paste.svg",
                        getClass().getClassLoader()));
                toolBar.add(pasteButton);
                toolBar.addSeparator();

                // ---- refreshButton ----
                refreshButton.setToolTipText("Refresh");
                refreshButton.setIcon(
                        new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "refresh.svg", getClass().getClassLoader()));
                toolBar.add(refreshButton);
                toolBar.addSeparator();

                // ---- showToggleButton ----
                showToggleButton.setSelected(true);
                showToggleButton.setToolTipText("Show Details");
                showToggleButton.setIcon(
                        new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "show.svg", getClass().getClassLoader()));
                toolBar.add(showToggleButton);
            }
            toolBarPanel.add(toolBar, BorderLayout.CENTER);

            add(toolBarPanel, BorderLayout.PAGE_START);
        }

        // ======== contentPanel ========
        {
            contentPanel.setLayout(new MigLayout(
                    "insets dialog,hidemode 3",
                    // columns
                    "[grow,fill]",
                    // rows
                    "[grow,fill]"));

            // ======== tabbedPane ========
            {
                tabbedPane.setPreferredSize(new Dimension(400, 400));
                tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
                tabbedPane.addChangeListener(e -> selectedTabChanged());

                JScrollPane scrollPane1 = new JScrollPane(basicComponentsPanel);
                scrollPane1.setBorder(BorderFactory.createEmptyBorder());
                scrollPane1.getVerticalScrollBar().setUnitIncrement(16);
                tabbedPane.addTab("Basic Components", scrollPane1);

                JScrollPane scrollPane2 = new JScrollPane(moreComponentsPanel);
                scrollPane2.setBorder(BorderFactory.createEmptyBorder());
                scrollPane2.getVerticalScrollBar().setUnitIncrement(16);
                tabbedPane.addTab("More Components", scrollPane2);

                JScrollPane scrollPane3 = new JScrollPane(dataComponentsPanel);
                scrollPane3.setBorder(BorderFactory.createEmptyBorder());
                scrollPane3.getVerticalScrollBar().setUnitIncrement(16);
                tabbedPane.addTab("Data Components", scrollPane3);

                JScrollPane scrollPane4 = new JScrollPane(tabsPanel);
                scrollPane4.setBorder(BorderFactory.createEmptyBorder());
                scrollPane4.getVerticalScrollBar().setUnitIncrement(16);
                tabbedPane.addTab("Tabs", scrollPane4);

                JScrollPane scrollPane5 = new JScrollPane(optionPanePanel);
                scrollPane5.setBorder(BorderFactory.createEmptyBorder());
                scrollPane5.getVerticalScrollBar().setUnitIncrement(16);
                tabbedPane.addTab("Option Pane", scrollPane5);

                JScrollPane scrollPane6 = new JScrollPane(extrasPanel);
                scrollPane6.setBorder(BorderFactory.createEmptyBorder());
                scrollPane6.getVerticalScrollBar().setUnitIncrement(16);
                tabbedPane.addTab("Extras", scrollPane6);
            }
            contentPanel.add(tabbedPane, "cell 0 0, grow");
            add(contentPanel, BorderLayout.CENTER);
        }
        add(controlBar, BorderLayout.SOUTH);

        // ======== themesPanelPanel ========
        {
            themesPanelPanel.setLayout(new BorderLayout());

            // ======== winFullWindowContentButtonsPlaceholder ========
            {
                winFullWindowContentButtonsPlaceholder.setLayout(new FlowLayout());
            }
            themesPanelPanel.add(winFullWindowContentButtonsPlaceholder, BorderLayout.NORTH);
            themesPanelPanel.add(themesPanel, BorderLayout.CENTER);
        }
        add(themesPanelPanel, BorderLayout.EAST);

        // ---- buttonGroup1 ----
        ButtonGroup buttonGroup1 = new ButtonGroup();
        buttonGroup1.add(radioButtonMenuItem1);
        buttonGroup1.add(radioButtonMenuItem2);
        buttonGroup1.add(radioButtonMenuItem3);
        // JFormDesigner - End of component initialization //GEN-END:initComponents

        // add "Users" button to menubar
        FlatButton usersButton = new FlatButton();
        usersButton
                .setIcon(new FlatSVGIcon(GuiResources.FLATLAF_ICONS_PATH + "users.svg", getClass().getClassLoader()));
        usersButton.setButtonType(ButtonType.toolBarButton);
        usersButton.setFocusable(false);
        usersButton.addActionListener(e -> JOptionPane.showMessageDialog(null, "Hello User! How are you?", "User",
                JOptionPane.INFORMATION_MESSAGE));
        menuBar.add(Box.createGlue());
        menuBar.add(usersButton);

        cutMenuItem.addActionListener(new DefaultEditorKit.CutAction());
        copyMenuItem.addActionListener(new DefaultEditorKit.CopyAction());
        pasteMenuItem.addActionListener(new DefaultEditorKit.PasteAction());

        scrollingPopupMenu.add("Large menus are scrollable");
        scrollingPopupMenu.add("Use mouse wheel to scroll");
        scrollingPopupMenu.add("Or use up/down arrows at top/bottom");
        for (int i = 1; i <= 100; i++)
            scrollingPopupMenu.add("Item " + i);

        if (supportsFlatLafWindowDecorations()) {
            windowDecorationsCheckBoxMenuItem.setSelected(SystemInfo.isLinux
                    ? JFrame.isDefaultLookAndFeelDecorated()
                    : FlatLaf.isUseNativeWindowDecorations());
            menuBarEmbeddedCheckBoxMenuItem.setSelected(UIManager.getBoolean("TitlePane.menuBarEmbedded"));
            unifiedTitleBarMenuItem.setSelected(UIManager.getBoolean("TitlePane.unifiedBackground"));
            showTitleBarIconMenuItem.setSelected(UIManager.getBoolean("TitlePane.showIcon"));
        } else {
            unsupported(windowDecorationsCheckBoxMenuItem);
            unsupported(menuBarEmbeddedCheckBoxMenuItem);
            unsupported(unifiedTitleBarMenuItem);
            unsupported(showTitleBarIconMenuItem);
        }

        if (SystemInfo.isMacOS)
            unsupported(underlineMenuSelectionMenuItem);

        if ("false".equals(System.getProperty("flatlaf.animatedLafChange")))
            animatedLafChangeMenuItem.setSelected(false);

        // on macOS, panel left to toolBar is a placeholder for title bar buttons in
        // fullWindowContent mode
        macFullWindowContentButtonsPlaceholder.putClientProperty(
                FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, "mac zeroInFullScreen");

        // on Windows/Linux, panel above themesPanel is a placeholder for title bar
        // buttons in fullWindowContent mode
        winFullWindowContentButtonsPlaceholder
                .putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, "win");

        // uncomment this line to see title bar buttons placeholders in
        // fullWindowContent mode
        // UIManager.put( "FlatLaf.debug.panel.showPlaceholders", true );

        // remove contentPanel bottom insets
        MigLayout layout = (MigLayout) contentPanel.getLayout();
        LC lc = ConstraintParser.parseLayoutConstraint((String) layout.getLayoutConstraints());
        UnitValue[] insets = lc.getInsets();
        lc.setInsets(new UnitValue[] {
                insets[0],
                insets[1],
                new UnitValue(0, UnitValue.PIXEL, null),
                insets[3]
        });
        layout.setLayoutConstraints(lc);
    }

    private void unsupported(JCheckBoxMenuItem menuItem) {
        menuItem.setEnabled(false);
        menuItem.setSelected(false);
        menuItem.setToolTipText("Not supported on this platform.");
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY //GEN-BEGIN:variables
    private JMenuBar menuBar;
    private JMenuItem exitMenuItem;
    private JMenu scrollingPopupMenu;
    private JMenuItem htmlMenuItem;
    private JMenu zoomMenu;
    private JMenu fontMenu;
    private JMenu optionsMenu;
    private JCheckBoxMenuItem windowDecorationsCheckBoxMenuItem;
    private JCheckBoxMenuItem menuBarEmbeddedCheckBoxMenuItem;
    private JCheckBoxMenuItem unifiedTitleBarMenuItem;
    private JCheckBoxMenuItem showTitleBarIconMenuItem;
    private JCheckBoxMenuItem underlineMenuSelectionMenuItem;
    private JCheckBoxMenuItem alwaysShowMnemonicsMenuItem;
    private JCheckBoxMenuItem animatedLafChangeMenuItem;
    private JMenuItem aboutMenuItem;
    private JToolBar toolBar;
    private JTabbedPane tabbedPane;
    private ControlBar controlBar;
    IJThemesPanel themesPanel;
    // JFormDesigner - End of variables declaration //GEN-END:variables

    // ---- class AccentColorIcon ----------------------------------------------

    private static class AccentColorIcon
            extends FlatAbstractIcon {
        private final String colorKey;

        AccentColorIcon(String colorKey) {
            super(16, 16, null);
            this.colorKey = colorKey;
        }

        @Override
        protected void paintIcon(Component c, Graphics2D g) {
            Color color = UIManager.getColor(colorKey);
            if (color == null)
                color = Color.lightGray;
            else if (!c.isEnabled()) {
                color = FlatLaf.isLafDark()
                        ? ColorFunctions.shade(color, 0.5f)
                        : ColorFunctions.tint(color, 0.6f);
            }

            g.setColor(color);
            g.fillRoundRect(1, 1, width - 2, height - 2, 5, 5);
        }
    }
}
