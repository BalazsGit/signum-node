package brs.gui.configuration;

import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class LookAndFeelProfile {
    private final String name;
    private String themeClassName;
    private Font globalFont;
    private Font consoleFont;
    private Map<String, Color> colorOverrides = new HashMap<>();

    public LookAndFeelProfile(String name) {
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
        LookAndFeelProfile that = (LookAndFeelProfile) o;
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

    public LookAndFeelProfile copy(String newName) {
        LookAndFeelProfile clone = new LookAndFeelProfile(newName);
        clone.setThemeClassName(this.themeClassName);
        clone.setGlobalFont(this.globalFont);
        clone.setConsoleFont(this.consoleFont);
        clone.setColorOverrides(this.colorOverrides);
        return clone;
    }
}
