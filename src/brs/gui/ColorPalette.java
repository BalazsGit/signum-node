package brs.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public class ColorPalette {
    private static final Logger logger = LoggerFactory.getLogger(ColorPalette.class);
    private final String name;
    private final Map<String, Color> colors;

    ColorPalette(String name, Map<String, Color> colors) {
        this.name = name;
        this.colors = Collections.unmodifiableMap(colors);
    }

    ColorPalette(ColorPalette basePalette, Map<String, Color> overrides) {
        this.name = basePalette.getName() + " (with overrides)";
        Map<String, Color> newColors = new ConcurrentHashMap<>(basePalette.colors);
        newColors.putAll(overrides);
        this.colors = Collections.unmodifiableMap(newColors);
    }

    public Set<String> getAllKeys() {
        return colors.keySet();
    }

    public String getName() {
        return name;
    }

    public Color getColor(String key, Color defaultValue) {
        return colors.getOrDefault(key, defaultValue);
    }

    public Map<String, Color> getColors() {
        return colors;
    }

    public static ColorPalette loadFromResources(String resourcePath) {
        try (InputStream is = ColorPalette.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger.warn("Color palette resource not found: {}", resourcePath);
                return new ColorPalette("Empty", Collections.emptyMap());
            }
            try (InputStreamReader reader = new InputStreamReader(is)) {
                Gson gson = new Gson();
                Type type = new TypeToken<Map<String, String>>() {
                }.getType();
                Map<String, String> stringMap = gson.fromJson(reader, type);

                String paletteName = stringMap.getOrDefault("name", "Unnamed");
                Map<String, Color> colorMap = new ConcurrentHashMap<>();
                for (Map.Entry<String, String> entry : stringMap.entrySet()) {
                    if (!"name".equals(entry.getKey()) && !entry.getKey().startsWith("_")) {
                        try {
                            colorMap.put(entry.getKey(), Color.decode(entry.getValue()));
                        } catch (NumberFormatException e) {
                            logger.warn("Invalid color format for key '{}' in palette '{}': {}", entry.getKey(),
                                    paletteName, entry.getValue());
                        }
                    }
                }
                return new ColorPalette(paletteName, colorMap);
            }
        } catch (Exception e) {
            logger.error("Failed to load color palette from resource: {}", resourcePath, e);
            return new ColorPalette("Error", Collections.emptyMap());
        }
    }
}
