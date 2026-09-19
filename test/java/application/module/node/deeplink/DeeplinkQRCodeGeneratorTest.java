package application.module.node.deeplink;

import application.module.node.common.TestConstants;
import application.module.node.feesuggestions.FeeSuggestionType;
import com.google.zxing.WriterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DeeplinkQRCodeGeneratorTest {
    private DeeplinkQRCodeGenerator deeplinkQRCodeGenerator;

    @BeforeEach
    public void setUpDeeplinkQrCodeGeneratorTest() {
        deeplinkQRCodeGenerator = new DeeplinkQRCodeGenerator();
    }

    @Test
    public void testDeeplinkQrCodeGenerator() throws WriterException {
        BufferedImage image = deeplinkQRCodeGenerator.generateRequestSignumDeepLinkQRCode(
                TestConstants.TEST_ACCOUNT_NUMERIC_ID, TestConstants.TEN_SIGNA, FeeSuggestionType.STANDARD, 0L, "Test!",
                true);
        assertNotNull(image);
    }
}
