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

package application.module.brs.gui.laf;

import java.awt.Dimension;
import java.util.prefs.Preferences;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import com.formdev.flatlaf.FlatLaf;
import application.module.brs.gui.GuiResources;
import com.formdev.flatlaf.extras.FlatInspector;
import com.formdev.flatlaf.extras.FlatUIDefaultsInspector;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.fonts.roboto.FlatRobotoFont;
import com.formdev.flatlaf.fonts.roboto_mono.FlatRobotoMonoFont;
import com.formdev.flatlaf.util.SystemFileChooser;
import com.formdev.flatlaf.util.SystemInfo;

/**
 * @author Karl Tauber
 */
public class FlatLafCommon {
    static final String PREFS_ROOT_PATH = "/flatlaf-settings";
    static final String KEY_TAB = "tab";

    static boolean screenshotsMode = Boolean.parseBoolean(System.getProperty("flatlaf.settings.screenshotsMode"));

    public static void main(String[] args) {
        // macOS (see https://www.formdev.com/flatlaf/macos/)
        if (SystemInfo.isMacOS) {
            // enable screen menu bar
            // (moves menu bar from JFrame window to top of screen)
            System.setProperty("apple.laf.useScreenMenuBar", "true");

            // application name used in screen menu bar
            // (in first menu after the "apple" menu)
            System.setProperty("apple.awt.application.name", "FlatLaf Preview");

            // appearance of window title bars
            // possible values:
            // - "system": use current macOS appearance (light or dark)
            // - "NSAppearanceNameAqua": use light appearance
            // - "NSAppearanceNameDarkAqua": use dark appearance
            // (must be set on main thread and before AWT/Swing is initialized;
            // setting it on AWT thread does not work)
            System.setProperty("apple.awt.application.appearance", "system");
        }

        // Linux
        if (SystemInfo.isLinux) {
            // enable custom window decorations
            JFrame.setDefaultLookAndFeelDecorated(true);
            JDialog.setDefaultLookAndFeelDecorated(true);
        }

        if (FlatLafCommon.screenshotsMode && !SystemInfo.isJava_9_orLater
                && System.getProperty("flatlaf.uiScale") == null)
            System.setProperty("flatlaf.uiScale", "2x");

        FlatLafPrefs.init(PREFS_ROOT_PATH);
        FlatLafPrefs.initSystemScale();

        // SystemFileChooser state storage
        SystemFileChooser.setStateStore(new SystemFileChooser.StateStore() {
            private static final String KEY_PREFIX = "fileChooser.";
            private final Preferences state = Preferences.userRoot().node(PREFS_ROOT_PATH);

            @Override
            public String get(String key, String def) {
                String value = state.get(KEY_PREFIX + key, def);
                System.out.println("SystemFileChooser State GET " + key + " = " + value);
                return value;
            }

            @Override
            public void put(String key, String value) {
                System.out.println("SystemFileChooser State PUT " + key + " = " + value);
                if (value != null)
                    state.put(KEY_PREFIX + key, value);
                else
                    state.remove(KEY_PREFIX + key);
            }
        });

        SwingUtilities.invokeLater(() -> {
            // install fonts for lazy loading
            FlatInterFont.installLazy();
            FlatJetBrainsMonoFont.installLazy();
            FlatRobotoFont.installLazy();
            FlatRobotoMonoFont.installLazy();

            // use Inter font by default
            // FlatLaf.setPreferredFontFamily( FlatInterFont.FAMILY );
            // FlatLaf.setPreferredLightFontFamily( FlatInterFont.FAMILY_LIGHT );
            // FlatLaf.setPreferredSemiboldFontFamily( FlatInterFont.FAMILY_SEMIBOLD );

            // use Roboto font by default
            // FlatLaf.setPreferredFontFamily( FlatRobotoFont.FAMILY );
            // FlatLaf.setPreferredLightFontFamily( FlatRobotoFont.FAMILY_LIGHT );
            // FlatLaf.setPreferredSemiboldFontFamily( FlatRobotoFont.FAMILY_SEMIBOLD );

            // use JetBrains Mono font
            // FlatLaf.setPreferredMonospacedFontFamily( FlatJetBrainsMonoFont.FAMILY );

            // use Roboto Mono font
            // FlatLaf.setPreferredMonospacedFontFamily( FlatRobotoMonoFont.FAMILY );

            // install own repaint manager to fix repaint issues at 125%, 175%, 225%, ... on
            // Windows
            // HiDPIUtils.installHiDPIRepaintManager();

            // application specific UI defaults
            String packageName = GuiResources.FLATLAF_RESOURCE_PATH;
            if (packageName.endsWith("/")) {
                packageName = packageName.substring(0, packageName.length() - 1);
            }
            FlatLaf.registerCustomDefaultsSource(packageName);

            // set look and feel
            FlatLafPrefs.setupLaf(args);

            // install inspectors
            FlatInspector.install("ctrl shift alt X");
            FlatUIDefaultsInspector.install("ctrl shift alt Y");

            // create frame
            JFrame frame = new JFrame("FlatLaf Preview");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            FlatLafPanel panel = new FlatLafPanel();
            frame.add(panel);

            if (FlatLafCommon.screenshotsMode) {
                panel.setPreferredSize(SystemInfo.isJava_9_orLater
                        ? new Dimension(830, 440)
                        : new Dimension(1660, 880));
            }

            // show frame
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
