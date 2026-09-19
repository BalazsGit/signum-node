package application.module.node.web.api.http.handler;

import application.module.node.TransactionProcessor;
import application.module.node.common.QuickMocker;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.util.JSON;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.API_KEY_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.DONE_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.ERROR_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.ERROR_CODE_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;

class ClearUnconfirmedTransactionsTest {

    private ClearUnconfirmedTransactions t;

    private TransactionProcessor transactionProcessorMock;
    private PropertyService propertyService;

    private static final String KEY = "abc";

    @BeforeEach
    public void init() {
        transactionProcessorMock = mock(TransactionProcessor.class);
        propertyService = mock(PropertyService.class);

        ArrayList<String> keys = new ArrayList<>();
        keys.add(KEY);
        doReturn(keys).when(propertyService).getStringList(Props.API_ADMIN_KEY_LIST);

        this.t = new ClearUnconfirmedTransactions(transactionProcessorMock, propertyService);
    }

    @Test
    public void processRequest() {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        doReturn(KEY).when(req).getParameter(API_KEY_PARAMETER);

        final JsonObject result = ((JsonObject) t.processRequest(req));

        assertEquals(true, JSON.getAsBoolean(result.get(DONE_RESPONSE)));
    }

    @Test
    public void processRequestNotAllowed() {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        doReturn("").when(req).getParameter(API_KEY_PARAMETER);

        final JsonObject result = ((JsonObject) t.processRequest(req));

        assertEquals(7, JSON.getAsInt(result.get(ERROR_CODE_RESPONSE)));
    }

    @Test
    public void processRequest_runtimeExceptionOccurs() {
        final HttpServletRequest req = QuickMocker.httpServletRequest();

        doReturn(KEY).when(req).getParameter(API_KEY_PARAMETER);

        doThrow(new RuntimeException("errorMessage")).when(transactionProcessorMock).clearUnconfirmedTransactions();

        final JsonObject result = ((JsonObject) t.processRequest(req));

        assertEquals("java.lang.RuntimeException: errorMessage", JSON.getAsString(result.get(ERROR_RESPONSE)));
    }

    @Test
    public void requirePost() {
        assertTrue(t.requirePost());
    }
}
