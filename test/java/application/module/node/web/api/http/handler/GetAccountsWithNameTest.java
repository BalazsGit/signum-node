package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.SignumException;
import application.module.node.common.AbstractUnitTest;
import application.module.node.common.QuickMocker;
import application.module.node.services.AccountService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

import static application.module.node.web.api.http.common.Parameters.ACCOUNTS_RESPONSE;
import static application.module.node.web.api.http.common.Parameters.NAME_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

;

class GetAccountsWithNameTest extends AbstractUnitTest {

    private AccountService accountService;

    private GetAccountsWithName t;

    @BeforeEach
    public void setUp() {
        accountService = mock(AccountService.class);

        t = new GetAccountsWithName(accountService);
    }

    @Test
    public void processRequest() throws SignumException {
        final long targetAccountId = 4L;
        final String targetAccountName = "exampleAccountName";

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new QuickMocker.MockParam(NAME_PARAMETER, targetAccountName));

        final Account targetAccount = mock(Account.class);
        when(targetAccount.getId()).thenReturn(targetAccountId);
        when(targetAccount.getName()).thenReturn(targetAccountName);

        final Collection<Account> mockIterator = mockCollection(targetAccount);

        when(accountService.getAccountsWithName(targetAccountName)).thenReturn(mockIterator);

        final JsonObject resultOverview = (JsonObject) t.processRequest(req);
        assertNotNull(resultOverview);

        final JsonArray resultList = (JsonArray) resultOverview.get(ACCOUNTS_RESPONSE);
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
    }

    @Test
    public void processRequest_noAccountFound() throws SignumException {
        final String targetAccountName = "exampleAccountName";

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new QuickMocker.MockParam(NAME_PARAMETER, targetAccountName));

        final Collection<Account> mockIterator = mockCollection();

        when(accountService.getAccountsWithName(targetAccountName)).thenReturn(mockIterator);

        final JsonObject resultOverview = (JsonObject) t.processRequest(req);
        assertNotNull(resultOverview);

        final JsonArray resultList = (JsonArray) resultOverview.get(ACCOUNTS_RESPONSE);
        assertNotNull(resultList);
        assertEquals(0, resultList.size());
    }
}
