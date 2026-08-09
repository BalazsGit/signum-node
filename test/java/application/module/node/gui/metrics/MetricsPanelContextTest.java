package application.module.node.gui.metrics;

import application.module.node.BlockchainImpl;
import application.module.node.BlockchainProcessor;
import application.module.node.Generator;
import application.module.node.TransactionProcessorImpl;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.PropertyService;
import application.module.node.instance.NodeCoreContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MetricsPanelContext}.
 * <p>
 * Validates that the context correctly delegates all getter calls to the
 * wrapped {@link NodeCoreContext} and rejects null inputs.
 * </p>
 */
class MetricsPanelContextTest {

    private NodeCoreContext coreContext;
    private PropertyService propertyService;
    private BlockchainImpl blockchain;
    private BlockchainProcessor blockchainProcessor;
    private FluxCapacitor fluxCapacitor;
    private Generator generator;
    private TransactionProcessorImpl transactionProcessor;

    private MetricsPanelContext context;

    @BeforeEach
    void setUp() {
        // Create mocks manually (mockito-junit-jupiter not available in project)
        coreContext = mock(NodeCoreContext.class);
        propertyService = mock(PropertyService.class);
        blockchain = mock(BlockchainImpl.class);
        blockchainProcessor = mock(BlockchainProcessor.class);
        fluxCapacitor = mock(FluxCapacitor.class);
        generator = mock(Generator.class);
        transactionProcessor = mock(TransactionProcessorImpl.class);

        // Stub all getters on the mock coreContext
        when(coreContext.getPropertyService()).thenReturn(propertyService);
        when(coreContext.getBlockchain()).thenReturn(blockchain);
        when(coreContext.getBlockchainProcessor()).thenReturn(blockchainProcessor);
        when(coreContext.getFluxCapacitor()).thenReturn(fluxCapacitor);
        when(coreContext.getGenerator()).thenReturn(generator);
        when(coreContext.getTransactionProcessor()).thenReturn(transactionProcessor);

        context = new MetricsPanelContext(coreContext);
    }

    @Test
    void constructor_WithNullCoreContext_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, 
            () -> new MetricsPanelContext((application.module.node.instance.NodeCoreContext)null));
    }
    
    @Test
    void constructor_WithNullSignum_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, 
            () -> new MetricsPanelContext((application.module.node.Signum)null));
    }

    @Test
    void getCoreContext_ReturnsWrappedInstance() {
        assertSame(coreContext, context.getCoreContext());
    }

    @Test
    void getPropertyService_DelegatesToCoreContext() {
        assertSame(propertyService, context.getPropertyService());
        verify(coreContext).getPropertyService();
    }

    @Test
    void getBlockchain_DelegatesToCoreContext() {
        assertSame(blockchain, context.getBlockchain());
        verify(coreContext).getBlockchain();
    }

    @Test
    void getBlockchainProcessor_DelegatesToCoreContext() {
        assertSame(blockchainProcessor, context.getBlockchainProcessor());
        verify(coreContext).getBlockchainProcessor();
    }

    @Test
    void getFluxCapacitor_DelegatesToCoreContext() {
        assertSame(fluxCapacitor, context.getFluxCapacitor());
        verify(coreContext).getFluxCapacitor();
    }

    @Test
    void getGenerator_DelegatesToCoreContext() {
        assertSame(generator, context.getGenerator());
        verify(coreContext).getGenerator();
    }

    @Test
    void getTransactionProcessor_DelegatesToCoreContext() {
        assertSame(transactionProcessor, context.getTransactionProcessor());
        verify(coreContext).getTransactionProcessor();
    }

    @Test
    void getters_WhenCoreContextReturnsNull_ReturnsNull() {
        // Reset mocks to return null
        when(coreContext.getPropertyService()).thenReturn(null);
        when(coreContext.getBlockchain()).thenReturn(null);
        when(coreContext.getBlockchainProcessor()).thenReturn(null);
        when(coreContext.getFluxCapacitor()).thenReturn(null);
        when(coreContext.getGenerator()).thenReturn(null);
        when(coreContext.getTransactionProcessor()).thenReturn(null);

        assertNull(context.getPropertyService());
        assertNull(context.getBlockchain());
        assertNull(context.getBlockchainProcessor());
        assertNull(context.getFluxCapacitor());
        assertNull(context.getGenerator());
        assertNull(context.getTransactionProcessor());
    }
}