package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.common.JSONTestHelper;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.common.TestConstants;
import application.module.node.crypto.Crypto;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.TransactionType.SignaMining.REWARD_RECIPIENT_ASSIGNMENT;
import static application.module.node.web.api.http.common.Parameters.RECIPIENT_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class SetRewardRecipientTest extends AbstractTransactionTest {

    private SetRewardRecipient t;

    @Mock
    private ParameterService mockParameterService;
    @Mock
    private Blockchain mockBlockchain;
    @Mock
    private AccountService mockAccountService;
    @Mock
    private APITransactionManager apiTransactionManagerMock;

    @BeforeEach
    void setUp() {
        FluxCapacitor fluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new SetRewardRecipient(mockParameterService, mockBlockchain, mockAccountService, apiTransactionManagerMock, fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(new MockParam(RECIPIENT_PARAMETER, "123"));
        final Account mockSenderAccount = mock(Account.class);
        final Account mockRecipientAccount = mock(Account.class);

        when(mockRecipientAccount.getPublicKey()).thenReturn(Crypto.getPublicKey(TestConstants.TEST_SECRET_PHRASE));

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSenderAccount);
        when(mockAccountService.getAccount(eq(123L))).thenReturn(mockRecipientAccount);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.SignaMiningRewardRecipientAssignment attachment = (Attachment.SignaMiningRewardRecipientAssignment) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(REWARD_RECIPIENT_ASSIGNMENT, attachment.getTransactionType());
        }
    }

    @Test
    void processRequest_recipientAccountDoesNotExist_errorCode8() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(new MockParam(RECIPIENT_PARAMETER, "123"));
        final Account mockSenderAccount = mock(Account.class);

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSenderAccount);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(false).when(fluxCapacitor).getValue(eq(FluxValues.SMART_TOKEN));

            assertEquals(8, JSONTestHelper.errorCode(t.processRequest(req)));
        }
    }

    @Test
    void processRequest_recipientAccountDoesNotHavePublicKey_errorCode8() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(new MockParam(RECIPIENT_PARAMETER, "123"));
        final Account mockSenderAccount = mock(Account.class);
        final Account mockRecipientAccount = mock(Account.class);

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSenderAccount);
        when(mockAccountService.getAccount(eq(123L))).thenReturn(mockRecipientAccount);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(false).when(fluxCapacitor).getValue(eq(FluxValues.SMART_TOKEN));

            assertEquals(8, JSONTestHelper.errorCode(t.processRequest(req)));
        }
    }
}