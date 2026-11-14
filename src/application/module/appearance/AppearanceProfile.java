package application.module.appearance;

import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single appearance profile: theme, global font, console font and color
 * overrides for the Signum Node GUI.
 *
 * <p>In the two-layer profile architecture, an {@code AppearanceProfile}
 * represents the effective (L0 + L2) appearance state. The L0 baseline comes
 * from {@link AppearanceDefaults}; the L2 layer is the on-disk
 * {@code .properties} file managed by {@link AppearanceProfileRepository}.</p>
 *
 * <p>Serialization to/from {@link java.util.Properties} uses the following keys:
 * <ul>
 *   <li>{@code theme.class} — the Look &amp; Feel class name</li>
 *   <li>{@code font.global.family|style|size} — global font</li>
 *   <li>{@code font.console.family|style|size} — console font</li>
 *   <li>{@code color.{key}} — color override (hex, e.g. {@code #FF0000})</li>
 * </ul>
 * Keys not present in the L2 file resolve to the L0 defaults (see
 * {@link AppearanceDefaults}).</p>
 */
public class AppearanceProfile {
    private final String name;
    private String themeClassName;
    private Font globalFont;
    private Font consoleFont;
    private Map<String, Color> colorOverrides = new HashMap<>();

    public AppearanceProfile(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getThemeClassName() {
        return themeClassName;
    }

    public void setThemeClassName(String themeClassName) {
        this.themeClassName = themeClassName;
    }

    public Font getGlobalFont() {
        return globalFont;
    }

    public void setGlobalFont(Font globalFont) {
        this.globalFont = globalFont;
    }

    public Font getConsoleFont() {
        return consoleFont;
    }

    public void setConsoleFont(Font consoleFont) {
        this.consoleFont = consoleFont;
    }

    public Map<String, Color> getColorOverrides() {
        return colorOverrides;
    }

    public void setColorOverrides(Map<String, Color> colorOverrides) {
        this.colorOverrides = colorOverrides != null ? new HashMap<>(colorOverrides) : new HashMap<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        AppearanceProfile that = (AppearanceProfile) o;
        return Objects.equals(themeClassName, that.themeClassName) &&
                fontsMatch(globalFont, that.globalFont) &&
                fontsMatch(consoleFont, that.consoleFont) &&
                Objects.equals(colorOverrides, that.colorOverrides);
    }

    private boolean fontsMatch(Font a, Font b) {
        if (a == b)
            return true;
        if (a == null || b == null) {
            // If one is null (Default), they are considered matching
            // if the other matches the system default font.
            Font nonNull = (a != null) ? a : b;
            Font systemDefault = UIManager.getFont("Label.font");
            if (systemDefault == null)
                return false;
            return nonNull.getFamily().equals(systemDefault.getFamily()) &&
                    nonNull.getSize() == systemDefault.getSize() &&
                    nonNull.getStyle() == systemDefault.getStyle();
        }
        return a.getFamily().equals(b.getFamily()) && a.getSize() == b.getSize() && a.getStyle() == b.getStyle();
    }

    @Override
    public int hashCode() {
        return Objects.hash(themeClassName, globalFont, consoleFont, colorOverrides);
    }

    public AppearanceProfile copy(String newName) {
        AppearanceProfile clone = new AppearanceProfile(newName);
        clone.setThemeClassName(this.themeClassName);
        clone.setGlobalFont(this.globalFont);
        clone.setConsoleFont(this.consoleFont);
        clone.setColorOverrides(this.colorOverrides);
        return clone;
    }

    // ── Properties serialization (two-layer L2 storage) ────────────────

    /**
     * Serializes this profile into a {@link java.util.Properties} object for
     * persistence as a {@code .properties} file (L2 layer).
     *
     * <p>Key format:</p>
     * <pre>
     * theme.class = com.formdev.flatlaf.FlatDarkLaf
     * font.global.family = Segoe UI
     * font.global.style = 0
     * font.global.size = 12
     * font.console.family = Consolas
     * font.console.style = 0
     * font.console.size = 12
     * color.&lt;uiKey&gt; = #RRGGBB
     * </pre>
     *
     * @return a Properties instance with all non-null settings
     */
    public java.util.Properties toProperties() {
        java.util.Properties props = new java.util.Properties();
        if (themeClassName != null) {
            props.setProperty("theme.class", themeClassName);
        }
        if (globalFont != null) {
            props.setProperty("font.global.family", globalFont.getFamily());
            props.setProperty("font.global.style", String.valueOf(globalFont.getStyle()));
            props.setProperty("font.global.size", String.valueOf(globalFont.getSize()));
        }
        if (consoleFont != null) {
            props.setProperty("font.console.family", consoleFont.getFamily());
            props.setProperty("font.console.style", String.valueOf(consoleFont.getStyle()));
            props.setProperty("font.console.size", String.valueOf(consoleFont.getSize()));
        }
        for (Map.Entry<String, Color> entry : colorOverrides.entrySet()) {
            props.setProperty("color." + entry.getKey(), String.format("#%06X", 0xFFFFFF & entry.getValue().getRGB()));
        }
        return props;
    }

    /**
     * Deserializes an {@link AppearanceProfile} from a {@link java.util.Properties}
     * object (L2 layer). Keys not present in the Properties are left as null/empty
     * (the caller is responsible for merging with L0 defaults via
     * {@link AppearanceDefaults}).
     *
     * @param name  the profile name
     * @param props the properties to read from (may be empty)
     * @return a new AppearanceProfile with the loaded values
     */
    public static AppearanceProfile fromProperties(String name, java.util.Properties props) {
        AppearanceProfile profile = new AppearanceProfile(name);
        if (props == null) {
            return profile;
        }
        String theme = props.getProperty("theme.class");
        if (theme != null && !theme.isBlank()) {
            profile.setThemeClassName(theme);
        }
        profile.setGlobalFont(parseFont(props, "font.global"));
        profile.setConsoleFont(parseFont(props, "font.console"));
        Map<String, Color> colors = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("color.")) {
                String colorKey = key.substring("color.".length());
                try {
                    colors.put(colorKey, Color.decode(props.getProperty(key)));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed color values
                }
            }
        }
        profile.setColorOverrides(colors);
        return profile;
    }

    private static Font parseFont(java.util.Properties props, String prefix) {
        String family = props.getProperty(prefix + ".family");
        if (family == null || family.isBlank()) {
            return null;
        }
        int style = Integer.parseInt(props.getProperty(prefix + ".style", "0"));
        int size = Integer.parseInt(props.getProperty(prefix + ".size", "12"));
        return new Font(family, style, size);
    }
}
