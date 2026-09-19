package application.module.node.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextUtilsTest {
    @Test
    public void testIsInAlphabet() {
        assertFalse(TextUtils.isInAlphabet("This string should not be okay"));
        assertTrue(TextUtils.isInAlphabet("ThisStringShouldBeOkay"));
        assertFalse(TextUtils.isInAlphabet("ThisStringHasPunctuation!"));
        assertFalse(TextUtils.isInAlphabet(new String(new byte[] { 0x00, 0x01, 0x02 })));

        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertTrue(TextUtils.isInAlphabet("ThisStringHasAnIInIt"));
    }
}
