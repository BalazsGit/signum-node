package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.Account.RewardRecipientAssignment;
import application.module.brs.SignumException;
import application.module.brs.common.AbstractUnitTest;
import application.module.brs.common.QuickMocker;
import application.module.brs.common.QuickMocker.MockParam;
import application.module.brs.services.AccountService;
import application.module.brs.services.ParameterService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

import static application.module.brs.web.api.http.common.Parameters.ACCOUNTS_RESPONSE;
import static application.module.brs.web.api.http.common.Parameters.ACCOUNT_PARAMETER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GetAccountsWithRewardRecipientTest extends AbstractUnitTest {

    private ParameterService parameterService;
    private AccountService accountService;

    private GetAccountsWithRewardRecipient t;

    @Before
    public void setUp() {
        parameterService = mock(ParameterService.class);
        accountService = mock(AccountService.class);

        t = new GetAccountsWithRewardRecipient(parameterService, accountService);
    }

    @Test
    public void processRequest() throws SignumException {
        final long targetAccountId = 4L;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ACCOUNT_PARAMETER, targetAccountId));

        final Account targetAccount = mock(Account.class);
        when(targetAccount.getId()).thenReturn(targetAccountId);

        when(parameterService.getAccount(eq(req))).thenReturn(targetAccount);

        final RewardRecipientAssignment assignment = mock(RewardRecipientAssignment.class);
        when(assignment.getAccountId()).thenReturn(targetAccountId);

        final Collection<RewardRecipientAssignment> assignmentIterator = mockCollection(assignment);

        when(accountService.getAccountsWithRewardRecipient(eq(targetAccountId))).thenReturn(assignmentIterator);

        final JsonObject resultOverview = (JsonObject) t.processRequest(req);
        assertNotNull(resultOverview);

        final JsonArray resultList = (JsonArray) resultOverview.get(ACCOUNTS_RESPONSE);
        assertNotNull(resultList);
        assertEquals(2, resultList.size());
    }

    @Test
    public void processRequest_withRewardRecipientAssignmentKnown() throws SignumException {
        final long targetAccountId = 4L;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ACCOUNT_PARAMETER, targetAccountId));

        final Account targetAccount = mock(Account.class);
        when(targetAccount.getId()).thenReturn(targetAccountId);

        when(parameterService.getAccount(eq(req))).thenReturn(targetAccount);

        final RewardRecipientAssignment assignment = mock(RewardRecipientAssignment.class);
        when(assignment.getAccountId()).thenReturn(targetAccountId);

        final Collection<RewardRecipientAssignment> assignmentIterator = mockCollection(assignment);

        when(accountService.getAccountsWithRewardRecipient(eq(targetAccountId))).thenReturn(assignmentIterator);
        when(accountService.getRewardRecipientAssignment(eq(targetAccount))).thenReturn(assignment);

        final JsonObject resultOverview = (JsonObject) t.processRequest(req);
        assertNotNull(resultOverview);

        final JsonArray resultList = (JsonArray) resultOverview.get(ACCOUNTS_RESPONSE);
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
    }
}
