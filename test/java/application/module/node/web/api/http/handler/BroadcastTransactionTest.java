package application.module.node.web.api.http.handler;

import application.module.node.Signum;
import application.module.node.SignumException;
import application.module.node.Transaction;
import application.module.node.TransactionProcessor;
import application.module.node.common.QuickMocker;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.PropertyService;
import application.module.node.services.ParameterService;
import application.module.node.services.TransactionService;
import application.module.node.util.JSON;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.TRANSACTION_BYTES_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.TRANSACTION_JSON_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BroadcastTransactionTest {

    private BroadcastTransaction t;

    @Mock
    private TransactionProcessor transactionProcessorMock;
    @Mock
    private ParameterService parameterServiceMock;
    @Mock
    private TransactionService transactionServiceMock;
    @Mock
    private PropertyService propertyServiceMock;

    @BeforeEach
    void setUp() {
        FluxCapacitor mockFluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new BroadcastTransaction(transactionProcessorMock, parameterServiceMock, transactionServiceMock,
                propertyServiceMock, mockFluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
        final String mockTransactionBytesParameter = "mockTransactionBytesParameter";
        final String mockTransactionJson = "mockTransactionJson";

        final String mockTransactionStringId = "mockTransactionStringId";
        final String mockTransactionFullHash = "mockTransactionFullHash";

        final HttpServletRequest req = mock(HttpServletRequest.class);
        final Transaction mockTransaction = mock(Transaction.class);

        when(mockTransaction.getStringId()).thenReturn(mockTransactionStringId);
        when(mockTransaction.getFullHash()).thenReturn(mockTransactionFullHash);

        when(req.getParameter(TRANSACTION_BYTES_PARAMETER)).thenReturn(mockTransactionBytesParameter);
        when(req.getParameter(TRANSACTION_JSON_PARAMETER)).thenReturn(mockTransactionJson);

        when(parameterServiceMock.parseTransaction(eq(mockTransactionBytesParameter), eq(mockTransactionJson)))
                .thenReturn(mockTransaction);

        final JsonObject result = (JsonObject) t.processRequest(req);

        verify(transactionProcessorMock).broadcast(eq(mockTransaction));

        assertEquals(mockTransactionStringId, JSON.getAsString(result.get(TRANSACTION_RESPONSE)));
        assertEquals(mockTransactionFullHash, JSON.getAsString(result.get(FULL_HASH_RESPONSE)));
    }

    @Test
    void processRequest_validationException() throws SignumException {
        final String mockTransactionBytesParameter = "mockTransactionBytesParameter";
        final String mockTransactionJson = "mockTransactionJson";

        final HttpServletRequest req = mock(HttpServletRequest.class);
        final Transaction mockTransaction = mock(Transaction.class);

        when(req.getParameter(TRANSACTION_BYTES_PARAMETER)).thenReturn(mockTransactionBytesParameter);
        when(req.getParameter(TRANSACTION_JSON_PARAMETER)).thenReturn(mockTransactionJson);

        when(parameterServiceMock.parseTransaction(eq(mockTransactionBytesParameter), eq(mockTransactionJson)))
                .thenReturn(mockTransaction);

        org.mockito.Mockito.doThrow(SignumException.NotCurrentlyValidException.class)
                .when(transactionServiceMock).validate(eq(mockTransaction));

        final JsonObject result = (JsonObject) t.processRequest(req);

        assertEquals(4, JSON.getAsInt(result.get(ERROR_CODE_RESPONSE)));
        assertTrue(result.has(ERROR_DESCRIPTION_RESPONSE));
    }

    @Test
    void requirePost() {
        assertTrue(t.requirePost());
    }
}