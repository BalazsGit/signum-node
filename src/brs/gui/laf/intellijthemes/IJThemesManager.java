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

package brs.gui.laf.intellijthemes;

import brs.gui.GuiResources;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.formdev.flatlaf.json.Json;
import com.formdev.flatlaf.util.LoggingFacade;
import com.formdev.flatlaf.util.StringUtils;

/**
 * @author Karl Tauber
 */
class IJThemesManager {
    final List<IJThemeInfo> bundledThemes = new ArrayList<>();
    final List<IJThemeInfo> moreThemes = new ArrayList<>();
    private final Map<File, Long> lastModifiedMap = new HashMap<>();

    @SuppressWarnings("unchecked")
    void loadBundledThemes() {
        bundledThemes.clear();

        // load themes.json
        Map<String, Object> json;
        try {
            InputStream stream = getClass().getResourceAsStream("themes.json");
            if (stream == null)
                stream = getClass().getClassLoader()
                        .getResourceAsStream(GuiResources.FLATLAF_RESOURCE_PATH + "themes.json");
            if (stream == null)
                stream = getClass().getClassLoader()
                        .getResourceAsStream(GuiResources.FLATLAF_THEMES_PATH + "themes.json");

            if (stream == null) {
                LoggingFacade.INSTANCE.logSevere("Could not find themes.json", null);
                return;
            }

            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                json = (Map<String, Object>) Json.parse(reader);
            }
        } catch (IOException ex) {
            LoggingFacade.INSTANCE.logSevere("Failed to load themes.json", ex);
            return;
        }

        // add info about bundled themes
        for (Map.Entry<String, Object> e : json.entrySet()) {
            String resourceName = e.getKey();
            // Strip original package path if present, to load from our resource folder
            int lastSlash = resourceName.lastIndexOf('/');
            if (lastSlash >= 0) {
                resourceName = resourceName.substring(lastSlash + 1);
            }

            Map<String, String> value = (Map<String, String>) e.getValue();
            String name = value.get("name");
            boolean discontinued = Boolean.parseBoolean(value.get("discontinued"));
            boolean dark = Boolean.parseBoolean(value.get("dark"));
            String lafClassName = value.get("lafClassName");
            String license = value.get("license");
            String licenseFile = value.get("licenseFile");
            String pluginUrl = value.get("pluginUrl");
            String sourceCodeUrl = value.get("sourceCodeUrl");
            String sourceCodePath = value.get("sourceCodePath");

            bundledThemes.add(new IJThemeInfo(name, resourceName, discontinued, dark,
                    license, licenseFile, pluginUrl, sourceCodeUrl, sourceCodePath, null, lafClassName));
        }
    }

    void loadThemesFromDirectory() {
        // get current working directory
        File directory = new File("").getAbsoluteFile();

        File[] themeFiles = directory.listFiles((dir, name) -> {
            return name.endsWith(".theme.json") || name.endsWith(".properties");
        });
        if (themeFiles == null)
            return;

        lastModifiedMap.clear();
        lastModifiedMap.put(directory, directory.lastModified());

        moreThemes.clear();
        for (File f : themeFiles) {
            String fname = f.getName();
            String name = fname.endsWith(".properties")
                    ? StringUtils.removeTrailing(fname, ".properties")
                    : StringUtils.removeTrailing(fname, ".theme.json");
            moreThemes.add(new IJThemeInfo(name, null, false, false, null, null, null, null, null, f, null));
            lastModifiedMap.put(f, f.lastModified());
        }
    }

    boolean hasThemesFromDirectoryChanged() {
        for (Map.Entry<File, Long> e : lastModifiedMap.entrySet()) {
            if (e.getKey().lastModified() != e.getValue().longValue())
                return true;
        }
        return false;
    }
}
