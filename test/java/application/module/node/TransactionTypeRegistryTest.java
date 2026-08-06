package application.module.node;

import org.junit.Test;
import org.mockito.Mockito;

import signum.net.NetworkParameters;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TransactionTypeRegistry}.
 * Validates registry initialization, type lookups, and network parameter application.
 */
public class TransactionTypeRegistryTest {

    @Test
    public void constructor_PopulatesAllTypeFamilies() {
        // Act
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Assert
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();
        assertNotNull("Transaction types map should not be null", types);
        assertEquals("Should have 8 type families registered", 8, types.size());

        // Verify each family exists
        assertTrue("Payment family missing", types.containsKey(TransactionType.TYPE_PAYMENT));
        assertTrue("Messaging family missing", types.containsKey(TransactionType.TYPE_MESSAGING));
        assertTrue("ColoredCoins family missing", types.containsKey(TransactionType.TYPE_COLORED_COINS));
        assertTrue("DigitalGoods family missing", types.containsKey(TransactionType.TYPE_DIGITAL_GOODS));
        assertTrue("AccountControl family missing", types.containsKey(TransactionType.TYPE_ACCOUNT_CONTROL));
        assertTrue("SignaMining family missing", types.containsKey(TransactionType.TYPE_SIGNA_MINING));
        assertTrue("AdvancedPayment family missing", types.containsKey(TransactionType.TYPE_ADVANCED_PAYMENT));
        assertTrue("AutomatedTransactions family missing", types.containsKey(TransactionType.TYPE_AUTOMATED_TRANSACTIONS));
    }

    @Test
    public void constructor_PaymentFamilyHasThreeSubtypes() {
        // Act
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Assert
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();
        Map<Byte, TransactionType> paymentTypes = types.get(TransactionType.TYPE_PAYMENT);
        assertEquals("Payment should have 3 subtypes", 3, paymentTypes.size());
        assertNotNull("ORDINARY payment missing", paymentTypes.get(TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT));
        assertNotNull("MULTI_OUT payment missing", paymentTypes.get(TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_OUT));
        assertNotNull("MULTI_SAME_OUT payment missing", paymentTypes.get(TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_SAME_OUT));
    }

    @Test
    public void constructor_MessagingFamilyHasSixSubtypes() {
        // Act
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Assert
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();
        Map<Byte, TransactionType> messagingTypes = types.get(TransactionType.TYPE_MESSAGING);
        assertEquals("Messaging should have 6 subtypes", 6, messagingTypes.size());
    }

    @Test
    public void constructor_ColoredCoinsFamilyHasElevenSubtypes() {
        // Act
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Assert
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();
        Map<Byte, TransactionType> coloredCoinsTypes = types.get(TransactionType.TYPE_COLORED_COINS);
        assertEquals("ColoredCoins should have 11 subtypes", 11, coloredCoinsTypes.size());
    }

    @Test
    public void constructor_DigitalGoodsFamilyHasEightSubtypes() {
        // Act
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Assert
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();
        Map<Byte, TransactionType> digitalGoodsTypes = types.get(TransactionType.TYPE_DIGITAL_GOODS);
        assertEquals("DigitalGoods should have 8 subtypes", 8, digitalGoodsTypes.size());
    }

    @Test
    public void findTransactionType_ValidPaymentType_ReturnsOrdinary() {
        // Arrange
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Act
        TransactionType result = registry.findTransactionType(
                TransactionType.TYPE_PAYMENT.getType(),
                TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT);

        // Assert
        assertNotNull("Should find ordinary payment type", result);
        assertEquals("Should be ORDINARY instance", TransactionType.Payment.ORDINARY, result);
    }

    @Test
    public void findTransactionType_InvalidType_ReturnsNull() {
        // Arrange
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Act
        TransactionType result = registry.findTransactionType((byte) 99, (byte) 99);

        // Assert
        assertNull("Should return null for unknown type", result);
    }

    @Test
    public void findTransactionType_ValidTypeInvalidSubtype_ReturnsNull() {
        // Arrange
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Act
        TransactionType result = registry.findTransactionType(
                TransactionType.TYPE_PAYMENT.getType(),
                (byte) 99);

        // Assert
        assertNull("Should return null for unknown subtype", result);
    }

    @Test
    public void findTransactionType_AdvancedPaymentEscrow_ReturnsEscrowCreation() {
        // Arrange
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Act
        TransactionType result = registry.findTransactionType(
                TransactionType.TYPE_ADVANCED_PAYMENT.getType(),
                TransactionType.SUBTYPE_ADVANCED_PAYMENT_ESCROW_CREATION);

        // Assert
        assertNotNull("Should find escrow creation type", result);
        assertEquals("Should be ESCROW_CREATION instance", TransactionType.AdvancedPayment.ESCROW_CREATION, result);
    }

    @Test
    public void getTransactionTypes_ReturnsUnmodifiableMap() {
        // Arrange
        TransactionTypeRegistry registry = new TransactionTypeRegistry();
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();

        // Act & Assert
        try {
            types.put(TransactionType.TYPE_PAYMENT, new java.util.HashMap<>());
            fail("Should not allow modifications to returned map");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }

    @Test
    public void applyNetworkParameters_DelegatesToParams() {
        // Arrange
        TransactionTypeRegistry registry = new TransactionTypeRegistry();
        NetworkParameters params = Mockito.mock(NetworkParameters.class);

        // Act
        registry.applyNetworkParameters(params);

        // Assert
        Mockito.verify(params).adjustTransactionTypes(Mockito.any(Map.class));
    }

    @Test
    public void toString_ReturnsDescriptiveString() {
        // Arrange
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Act
        String result = registry.toString();

        // Assert
        assertNotNull("toString should not return null", result);
        assertTrue("Should contain class name", result.contains("TransactionTypeRegistry"));
        assertTrue("Should contain registeredTypes count", result.contains("registeredTypes="));
    }
}