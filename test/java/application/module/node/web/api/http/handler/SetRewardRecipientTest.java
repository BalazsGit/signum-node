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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.TransactionType.SignaMining.REWARD_RECIPIENT_ASSIGNMENT;
import static application.module.node.web.api.http.common.Parameters.RECIPIENT_PARAMETER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest(Signum.class)
public class SetRewardRecipientTest extends AbstractTransactionTest {

    private SetRewardRecipient t;

    private ParameterService parameterServiceMock;
    private Blockchain blockchainMock;
    private AccountService accountServiceMock;
    private APITransactionManager apiTransactionManagerMock;

    @Before
    public void setUp() {
        parameterServiceMock = mock(ParameterService.class);
        blockchainMock = mock(Blockchain.class);
        accountServiceMock = mock(AccountService.class);
        apiTransactionManagerMock = mock(APITransactionManager.class);

        t = new SetRewardRecipient(parameterServiceMock, blockchainMock, accountServiceMock, apiTransactionManagerMock);
    }

    @Test
    public void processRequest() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(new MockParam(RECIPIENT_PARAMETER, "123"));
        final Account mockSenderAccount = mock(Account.class);
        final Account mockRecipientAccount = mock(Account.class);

        when(mockRecipientAccount.getPublicKey()).thenReturn(Crypto.getPublicKey(TestConstants.TEST_SECRET_PHRASE));

        when(parameterServiceMock.getAccount(eq(req))).thenReturn(mockSenderAccount);
        when(accountServiceMock.getAccount(eq(123L))).thenReturn(mockRecipientAccount);

        mockStatic(Signum.class);
        final FluxCapacitor fluxCapacitor = QuickMocker
                .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
        when(Signum.getFluxCapacitor()).thenReturn(fluxCapacitor);
        doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

        final Attachment.SignaMiningRewardRecipientAssignment attachment = (Attachment.SignaMiningRewardRecipientAssignment) attachmentCreatedTransaction(
                () -> t.processRequest(req), apiTransactionManagerMock);
        assertNotNull(attachment);

        assertEquals(REWARD_RECIPIENT_ASSIGNMENT, attachment.getTransactionType());
    }

    @Test
    public void processRequest_recipientAccountDoesNotExist_errorCode8() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(new MockParam(RECIPIENT_PARAMETER, "123"));
        final Account mockSenderAccount = mock(Account.class);

        when(parameterServiceMock.getAccount(eq(req))).thenReturn(mockSenderAccount);

        mockStatic(Signum.class);
        final FluxCapacitor fluxCapacitor = QuickMocker
                .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
        when(Signum.getFluxCapacitor()).thenReturn(fluxCapacitor);
        doReturn(false).when(fluxCapacitor).getValue(eq(FluxValues.SMART_TOKEN));

        assertEquals(8, JSONTestHelper.errorCode(t.processRequest(req)));
    }

    @Test
    public void processRequest_recipientAccountDoesNotHavePublicKey_errorCode8() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(new MockParam(RECIPIENT_PARAMETER, "123"));
        final Account mockSenderAccount = mock(Account.class);
        final Account mockRecipientAccount = mock(Account.class);

        when(parameterServiceMock.getAccount(eq(req))).thenReturn(mockSenderAccount);
        when(accountServiceMock.getAccount(eq(123L))).thenReturn(mockRecipientAccount);

        mockStatic(Signum.class);
        final FluxCapacitor fluxCapacitor = QuickMocker
                .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
        when(Signum.getFluxCapacitor()).thenReturn(fluxCapacitor);
        doReturn(false).when(fluxCapacitor).getValue(eq(FluxValues.SMART_TOKEN));

        assertEquals(8, JSONTestHelper.errorCode(t.processRequest(req)));
    }
}
