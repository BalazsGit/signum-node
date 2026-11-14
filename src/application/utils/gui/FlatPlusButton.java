package application.utils.gui;

/**
 * The flat "+" button of the configuration list editors' add rows and the module
 * logging profile's add-key row. It carries the shared thin plus glyph
 * ({@link PlusGlyphIcon}, via {@link ProfileActionIcon#NEW_PROFILE}) as a
 * <b>static</b> icon: it does <em>not</em> grow on hover.
 *
 * <p>Grow-on-hover is reserved for the <b>header</b> function buttons (the
 * profile-toolbar New Profile button and the node toolbar's icon buttons, all
 * installed through {@link HoverScaleIcon}). These inline "+" add buttons live in
 * the field area, on the same row as each list's trash and help ("?") icons, so
 * they stay a fixed size and only the shared rounded hover highlight from
 * {@link FlatIconButton} reacts to the pointer.
 *
 * <p>This is also distinct from the node-tab trailing "+"
 * ({@code NodePanel#NewProfileButton}): that button is likewise a static,
 * non-growing glyph.
 */
public final class FlatPlusButton extends FlatIconButton {

    /**
     * Creates the flat "+" at the standard toolbar icon size, using the shared
     * thin plus glyph ({@link PlusGlyphIcon}). The glyph is a <b>static</b>
     * (non-growing) icon: like every inline list-editor "+" (the "Allowed IPs",
     * "Admin Password", database-file and logging-key add rows), it does not grow
     * on hover — only the {@link FlatIconButton} rounded highlight reacts to the
     * pointer. Callers must not (and need not) set the icon themselves.
     */
    public FlatPlusButton() {
        setIcon(ProfileActionIcon.NEW_PROFILE.normal(GuiConstants.getToolBarIconSize()));
    }
}