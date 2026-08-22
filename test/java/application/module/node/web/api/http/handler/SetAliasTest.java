package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.AliasService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.TransactionType.Messaging.ALIAS_ASSIGNMENT;
import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.ALIAS_NAME_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.ALIAS_URI_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class SetAliasTest extends AbstractTransactionTest {

    private SetAlias t;

    @Mock
    private ParameterService mockParameterService;
    @Mock
    private Blockchain mockBlockchain;
    @Mock
    private AliasService mockAliasService;
    @Mock
    private APITransactionManager apiTransactionManagerMock;

    @BeforeEach
    void setUp() {
        FluxCapacitor fluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new SetAlias(mockParameterService, mockBlockchain, mockAliasService, apiTransactionManagerMock, fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
        final String aliasNameParameter = "aliasNameParameter";
        final String aliasUrl = "aliasUrl";

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ALIAS_NAME_PARAMETER, aliasNameParameter),
                new MockParam(ALIAS_URI_PARAMETER, aliasUrl));

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.MessagingAliasAssignment attachment = (Attachment.MessagingAliasAssignment) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(ALIAS_ASSIGNMENT, attachment.getTransactionType());
            assertEquals(aliasNameParameter, attachment.getAliasName());
            assertEquals(aliasUrl, attachment.getAliasUri());
        }
    }

    @Test
    void processRequest_missingAliasName() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ALIAS_NAME_PARAMETER, null),
                new MockParam(ALIAS_URI_PARAMETER, "aliasUrl"));

        assertEquals(MISSING_ALIAS_NAME, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectAliasLength_nameOnlySpaces() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ALIAS_NAME_PARAMETER, "  "),
                new MockParam(ALIAS_URI_PARAMETER, null));

        assertEquals(INCORRECT_ALIAS_LENGTH, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectAliasLength_incorrectAliasName() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ALIAS_NAME_PARAMETER, "[]"),
                new MockParam(ALIAS_URI_PARAMETER, null));

        assertEquals(INCORRECT_ALIAS_NAME, t.processRequest(req));
    }

    @Test
    void processRequest_incorrectUriLengthWhenOver1000Characters() throws SignumException {
        final StringBuilder uriOver1000Characters = new StringBuilder();

        for (int i = 0; i < 1001; i++) {
            uriOver1000Characters.append("a");
        }

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ALIAS_NAME_PARAMETER, "name"),
                new MockParam(ALIAS_URI_PARAMETER, uriOver1000Characters.toString()));

        assertEquals(INCORRECT_URI_LENGTH, t.processRequest(req));
    }
}