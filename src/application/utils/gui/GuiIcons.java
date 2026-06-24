package application.utils.gui;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides access to the application's icon set using FontAwesome icons.
 * <p>
 * This class acts as a centralized factory for Swing Icons, ensuring:
 * <ul>
 *   <li>Consistent use of FontAwesome across all UI components</li>
 *   <li>Thread-safe FontAwesome font registration (prevents white rectangle rendering)</li>
 *   <li>Single source of truth for icon sizes and default colors</li>
 *   <li>Dynamic scaling relative to the global UI font size</li>
 * </ul>
 * Follows the same centralized accessor pattern as {@link GuiColors}.
 *
 * @see GuiColors
 * @see IconFontSwing
 */
public final class GuiIcons {

    /** Default icon size fallback when UIManager font is unavailable */
    private static final int DEFAULT_BASE_SIZE = 14;
    /** Minimum allowed icon size to prevent illegible icons */
    private static final int MIN_ICON_SIZE = 8;

    private static final AtomicBoolean FONT_REGISTERED = new AtomicBoolean(false);

    private GuiIcons() {
        // Prevent instantiation
    }

    // ====================================================================
    // Dynamic Size Calculation - Scales with global UI font
    // ====================================================================

    /**
     * Gets the base icon size derived from the current UIManager font.
     * This allows icons to scale proportionally when the global font size changes.
     *
     * @return Current base icon size in pixels, or default if UIManager unavailable
     */
    public static int getBaseSize() {
        try {
            Font font = UIManager.getFont("Label.font");
            if (font != null) {
                return Math.max(MIN_ICON_SIZE, font.getSize());
            }
        } catch (Exception ignored) {
            // UIManager not initialized yet
        }
        return DEFAULT_BASE_SIZE;
    }

    /**
     * Icon size: tiny indicators (e.g., tab status dots).
     * Approximately 70% of base font size.
     */
    public static int sizeTiny() {
        return Math.max(MIN_ICON_SIZE, Math.round(getBaseSize() * 0.7f));
    }

    /**
     * Icon size: small labels and help icons.
     * Equal to base font size.
     */
    public static int sizeSmall() {
        return getBaseSize();
    }

    /**
     * Icon size: toolbar buttons and standard actions.
     * Approximately 130% of base font size.
     */
    public static int sizeMedium() {
        return Math.round(getBaseSize() * 1.3f);
    }

    /**
     * Icon size: large toolbar items and dialogs.
     * Approximately 170% of base font size.
     */
    public static int sizeLarge() {
        return Math.round(getBaseSize() * 1.7f);
    }

    // ====================================================================
    // Legacy constants - Fixed fallback sizes (deprecated, use dynamic methods)
    // ====================================================================

    /** @deprecated Use {@link #sizeTiny()} for dynamic sizing */
    @Deprecated
    public static final int SIZE_TINY = 10;
    /** @deprecated Use {@link #sizeSmall()} for dynamic sizing */
    @Deprecated
    public static final int SIZE_SMALL = 14;
    /** @deprecated Use {@link #sizeMedium()} for dynamic sizing */
    @Deprecated
    public static final int SIZE_MEDIUM = 18;
    /** @deprecated Use {@link #sizeLarge()} for dynamic sizing */
    @Deprecated
    public static final int SIZE_LARGE = 24;

    // ====================================================================
    // Node Lifecycle State Icons
    // ====================================================================

    /** Green circle icon for a running node. */
    public static Icon running(int size) {
        return build(FontAwesome.CIRCLE, size, GuiColors.getPeerActive());
    }

    /** Yellow spinner icon for initializing state. */
    public static Icon initializing(int size) {
        return build(FontAwesome.SPINNER, size, new Color(255, 193, 7));
    }

    /** Yellow spinner icon for stopping state. */
    public static Icon stopping(int size) {
        return build(FontAwesome.SPINNER, size, new Color(255, 193, 7));
    }

    /** Red warning icon for error state. */
    public static Icon error(int size) {
        return build(FontAwesome.EXCLAMATION_TRIANGLE, size, GuiColors.getContrastRed());
    }

    /** Blue pause icon for paused state. */
    public static Icon paused(int size) {
        return build(FontAwesome.PAUSE, size, new Color(103, 58, 183));
    }

    /** Null icon for idle/stopped states (no icon displayed). */
    public static Icon idle(int size) {
        return null;
    }

    // ====================================================================
    // Common UI Action Icons
    // ====================================================================

    /** Refresh/reload icon. */
    public static Icon refresh() {
        return build(FontAwesome.REFRESH, SIZE_MEDIUM, GuiColors.getApplied());
    }

    public static Icon refresh(int size) {
        return build(FontAwesome.REFRESH, size, GuiColors.getApplied());
    }

    /** Delete/remove icon. */
    public static Icon delete() {
        return build(FontAwesome.TRASH, SIZE_MEDIUM, GuiColors.getContrastRed());
    }

    public static Icon delete(int size) {
        return build(FontAwesome.TRASH, size, GuiColors.getContrastRed());
    }

    /** Settings/configuration icon. */
    public static Icon settings() {
        return build(FontAwesome.COG, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon settings(int size) {
        return build(FontAwesome.COG, size, GuiColors.getButtonIcon());
    }

    /** Globe/web icon. */
    public static Icon globe() {
        return build(FontAwesome.GLOBE, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon globe(int size) {
        return build(FontAwesome.GLOBE, size, GuiColors.getButtonIcon());
    }

    /** Arrow left icon. */
    public static Icon arrowLeft() {
        return build(FontAwesome.ARROW_LEFT, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon arrowLeft(int size) {
        return build(FontAwesome.ARROW_LEFT, size, GuiColors.getButtonIcon());
    }

    /** Arrow right icon. */
    public static Icon arrowRight() {
        return build(FontAwesome.ARROW_RIGHT, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon arrowRight(int size) {
        return build(FontAwesome.ARROW_RIGHT, size, GuiColors.getButtonIcon());
    }

    /** Arrow up icon. */
    public static Icon arrowUp() {
        return build(FontAwesome.ARROW_UP, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon arrowUp(int size) {
        return build(FontAwesome.ARROW_UP, size, GuiColors.getButtonIcon());
    }

    /** Arrow down icon. */
    public static Icon arrowDown() {
        return build(FontAwesome.ARROW_DOWN, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon arrowDown(int size) {
        return build(FontAwesome.ARROW_DOWN, size, GuiColors.getButtonIcon());
    }

    /** Database icon. */
    public static Icon database() {
        return build(FontAwesome.DATABASE, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon database(int size) {
        return build(FontAwesome.DATABASE, size, GuiColors.getButtonIcon());
    }

    /** Power/shutdown icon. */
    public static Icon powerOff() {
        return build(FontAwesome.POWER_OFF, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon powerOff(int size) {
        return build(FontAwesome.POWER_OFF, size, GuiColors.getButtonIcon());
    }

    /** Play/start icon. */
    public static Icon play() {
        return build(FontAwesome.PLAY, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon play(int size) {
        return build(FontAwesome.PLAY, size, GuiColors.getButtonIcon());
    }

    /** Pencil/edit icon. */
    public static Icon edit() {
        return build(FontAwesome.PENCIL, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon edit(int size) {
        return build(FontAwesome.PENCIL, size, GuiColors.getButtonIcon());
    }

    /** Book/documentation icon. */
    public static Icon book() {
        return build(FontAwesome.BOOK, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon book(int size) {
        return build(FontAwesome.BOOK, size, GuiColors.getButtonIcon());
    }

    /** Wrench/tool icon. */
    public static Icon tool() {
        return build(FontAwesome.WRENCH, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon tool(int size) {
        return build(FontAwesome.WRENCH, size, GuiColors.getButtonIcon());
    }

    /** Scissors icon. */
    public static Icon scissors() {
        return build(FontAwesome.SCISSORS, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon scissors(int size) {
        return build(FontAwesome.SCISSORS, size, GuiColors.getButtonIcon());
    }

    /** Clock/time icon. */
    public static Icon clock() {
        return build(FontAwesome.CLOCK_O, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon clock(int size) {
        return build(FontAwesome.CLOCK_O, size, GuiColors.getButtonIcon());
    }

    /** Flask/experimental icon. */
    public static Icon flask() {
        return build(FontAwesome.FLASK, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon flask(int size) {
        return build(FontAwesome.FLASK, size, GuiColors.getButtonIcon());
    }

    /** Hamburger menu icon. */
    public static Icon menu() {
        return build(FontAwesome.BARS, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon menu(int size) {
        return build(FontAwesome.BARS, size, GuiColors.getButtonIcon());
    }

    /** Fire icon (Phoenix). */
    public static Icon fire() {
        return build(FontAwesome.FIRE, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon fire(int size) {
        return build(FontAwesome.FIRE, size, GuiColors.getButtonIcon());
    }

    /** Check-circle success icon. */
    public static Icon checkCircle() {
        return build(FontAwesome.CHECK_CIRCLE, SIZE_MEDIUM, new Color(0, 128, 0));
    }

    public static Icon checkCircle(int size) {
        return build(FontAwesome.CHECK_CIRCLE, size, new Color(0, 128, 0));
    }

    /** Backward/rewind icon. */
    public static Icon backward() {
        return build(FontAwesome.BACKWARD, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon backward(int size) {
        return build(FontAwesome.BACKWARD, size, GuiColors.getButtonIcon());
    }

    /** Step-backward icon. */
    public static Icon stepBackward() {
        return build(FontAwesome.STEP_BACKWARD, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon stepBackward(int size) {
        return build(FontAwesome.STEP_BACKWARD, size, GuiColors.getButtonIcon());
    }

    /** Window restore icon. */
    public static Icon windowRestore() {
        return build(FontAwesome.WINDOW_RESTORE, SIZE_MEDIUM, GuiColors.getButtonIcon());
    }

    public static Icon windowRestore(int size) {
        return build(FontAwesome.WINDOW_RESTORE, size, GuiColors.getButtonIcon());
    }

    /** Magic wand/convert icon. */
    public static Icon magic() {
        return build(FontAwesome.MAGIC, SIZE_MEDIUM, GuiColors.getHelpIcon());
    }

    public static Icon magic(int size) {
        return build(FontAwesome.MAGIC, size, GuiColors.getHelpIcon());
    }

    // ====================================================================
    // Generic Builder - Custom icon with custom color
    // ====================================================================

    /**
     * Builds a FontAwesome icon with the specified character, size and color.
     * Ensures FontAwesome font is registered before building.
     *
     * @param character The FontAwesome icon enum value (e.g., FontAwesome.CIRCLE)
     * @param size      The icon size in pixels
     * @param color     The icon color
     * @return A Swing Icon instance
     */
    public static Icon build(FontAwesome character, int size, Color color) {
        ensureFontAwesomeRegistered();
        return IconFontSwing.buildIcon(character, size, color);
    }

    // ====================================================================
    // Internal
    // ====================================================================

    /**
     * Ensures the FontAwesome font is registered with IconFontSwing.
     * Thread-safe: only registers once using AtomicBoolean.
     */
    private static void ensureFontAwesomeRegistered() {
        if (FONT_REGISTERED.compareAndSet(false, true)) {
            IconFontSwing.register(FontAwesome.getIconFont());
        }
    }
}