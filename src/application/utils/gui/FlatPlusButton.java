package application.utils.gui;

/**
 * The flat "+" button of the list editors' add rows — the SAME look as the
 * node-profile toolbar's "create a new profile" button
 * ({@code NodePanel#NewProfileButton}): a flat, borderless button carrying
 * the thin 16 px plus glyph ({@link PlusGlyphIcon}, pixel-identical to
 * {@code NodePanel#PlusGlyph}) that paints a rounded hover highlight on
 * rollover, so both "+" controls read as one design. Shared by the node
 * configuration list editors and the module logging profile's add-key row.
 */
public final class FlatPlusButton extends FlatIconButton {
}