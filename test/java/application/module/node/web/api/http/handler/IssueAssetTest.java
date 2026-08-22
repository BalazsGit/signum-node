package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.Constants.*;
import static application.module.node.TransactionType.ColoredCoins.ASSET_ISSUANCE;
import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class IssueAssetTest extends AbstractTransactionTest {

    private IssueAsset t;

    @Mock
    private ParameterService mockParameterService;
    @Mock
    private Blockchain mockBlockchain;
    @Mock
    private APITransactionManager apiTransactionManagerMock;

    @BeforeEach
    void setUp() {
        FluxCapacitor fluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new IssueAsset(mockParameterService, mockBlockchain, apiTransactionManagerMock, fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
        final String nameParameter = stringWithLength(MIN_ASSET_NAME_LENGTH + 1);
        final String descriptionParameter = stringWithLength(MAX_ASSET_DESCRIPTION_LENGTH - 1);
        final int decimalsParameter = 4;
        final int quantityQNTParameter = 5;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(NAME_PARAMETER, nameParameter),
                new MockParam(DESCRIPTION_PARAMETER, descriptionParameter),
                new MockParam(DECIMALS_PARAMETER, decimalsParameter),
                new MockParam(QUANTITY_QNT_PARAMETER, quantityQNTParameter));

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.ColoredCoinsAssetIssuance attachment = (Attachment.ColoredCoinsAssetIssuance) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(ASSET_ISSUANCE, attachment.getTransactionType());
            assertEquals(nameParameter, attachment.getName());
            assertEquals(descriptionParameter, attachment.getDescription());
            assertEquals(decimalsParameter, attachment.getDecimals());
            assertEquals(descriptionParameter, attachment.getDescription());
        }
    }

    @Test
    void processRequest_missingName() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        assertEquals(MISSING_NAME, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectAssetNameLength_smallerThanMin() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(NAME_PARAMETER, stringWithLength(MIN_ASSET_NAME_LENGTH - 1)));

        assertEquals(INCORRECT_ASSET_NAME_LENGTH, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectAssetNameLength_largerThanMax() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(NAME_PARAMETER, stringWithLength(MAX_ASSET_NAME_LENGTH + 1)));

        assertEquals(INCORRECT_ASSET_NAME_LENGTH, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectAssetName() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(NAME_PARAMETER, stringWithLength(MIN_ASSET_NAME_LENGTH + 1) + "["));

        assertEquals(INCORRECT_ASSET_NAME, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectAssetDescription() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(NAME_PARAMETER, stringWithLength(MIN_ASSET_NAME_LENGTH + 1)),
                new MockParam(DESCRIPTION_PARAMETER, stringWithLength(MAX_ASSET_DESCRIPTION_LENGTH + 1)));

        assertEquals(INCORRECT_ASSET_DESCRIPTION, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectDecimals_unParsable() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(NAME_PARAMETER, stringWithLength(MIN_ASSET_NAME_LENGTH + 1)),
                new MockParam(DESCRIPTION_PARAMETER, stringWithLength(MAX_ASSET_DESCRIPTION_LENGTH - 1)),
                new MockParam(DECIMALS_PARAMETER, "unParsable"));

        assertEquals(INCORRECT_DECIMALS, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectDecimals_negativeNumber() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(NAME_PARAMETER, stringWithLength(MIN_ASSET_NAME_LENGTH + 1)),
                new MockParam(DESCRIPTION_PARAMETER, stringWithLength(MAX_ASSET_DESCRIPTION_LENGTH - 1)),
                new MockParam(DECIMALS_PARAMETER, -5));

        assertEquals(INCORRECT_DECIMALS, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectDecimals_moreThan8() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(NAME_PARAMETER, stringWithLength(MIN_ASSET_NAME_LENGTH + 1)),
                new MockParam(DESCRIPTION_PARAMETER, stringWithLength(MAX_ASSET_DESCRIPTION_LENGTH - 1)),
                new MockParam(DECIMALS_PARAMETER, 9));

        assertEquals(INCORRECT_DECIMALS, t.processRequest(req));
    }
}