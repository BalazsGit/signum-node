package application.module.node;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import signum.net.NetworkParameters;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TransactionTypeRegistry}.
 * Validates registry initialization, type lookups, and network parameter application.
 */
class TransactionTypeRegistryTest {

    @Test
    public void constructor_PopulatesAllTypeFamilies() {
        // Act
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Assert
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();
        assertNotNull(types, "Transaction types map should not be null");
        assertEquals(8, types.size(), "Should have 8 type families registered");

        // Verify each family exists
        assertTrue(types.containsKey(TransactionType.TYPE_PAYMENT), "Payment family missing");
        assertTrue(types.containsKey(TransactionType.TYPE_MESSAGING), "Messaging family missing");
        assertTrue(types.containsKey(TransactionType.TYPE_COLORED_COINS), "ColoredCoins family missing");
        assertTrue(types.containsKey(TransactionType.TYPE_DIGITAL_GOODS), "DigitalGoods family missing");
        assertTrue(types.containsKey(TransactionType.TYPE_ACCOUNT_CONTROL), "AccountControl family missing");
        assertTrue(types.containsKey(TransactionType.TYPE_SIGNA_MINING), "SignaMining family missing");
        assertTrue(types.containsKey(TransactionType.TYPE_ADVANCED_PAYMENT), "AdvancedPayment family missing");
        assertTrue(types.containsKey(TransactionType.TYPE_AUTOMATED_TRANSACTIONS), "AutomatedTransactions family missing");
    }

    @Test
    public void constructor_PaymentFamilyHasThreeSubtypes() {
        // Act
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Assert
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();
        Map<Byte, TransactionType> paymentTypes = types.get(TransactionType.TYPE_PAYMENT);
        assertEquals(3, paymentTypes.size(), "Payment should have 3 subtypes");
        assertNotNull(paymentTypes.get(TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT), "ORDINARY payment missing");
        assertNotNull(paymentTypes.get(TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_OUT), "MULTI_OUT payment missing");
        assertNotNull(paymentTypes.get(TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_SAME_OUT), "MULTI_SAME_OUT payment missing");
    }

    @Test
    public void constructor_MessagingFamilyHasSixSubtypes() {
        // Act
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Assert
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();
        Map<Byte, TransactionType> messagingTypes = types.get(TransactionType.TYPE_MESSAGING);
        assertEquals(6, messagingTypes.size(), "Messaging should have 6 subtypes");
    }

    @Test
    public void constructor_ColoredCoinsFamilyHasElevenSubtypes() {
        // Act
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Assert
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();
        Map<Byte, TransactionType> coloredCoinsTypes = types.get(TransactionType.TYPE_COLORED_COINS);
        assertEquals(11, coloredCoinsTypes.size(), "ColoredCoins should have 11 subtypes");
    }

    @Test
    public void constructor_DigitalGoodsFamilyHasEightSubtypes() {
        // Act
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Assert
        Map<TransactionType.Type, Map<Byte, TransactionType>> types = registry.getTransactionTypes();
        Map<Byte, TransactionType> digitalGoodsTypes = types.get(TransactionType.TYPE_DIGITAL_GOODS);
        assertEquals(8, digitalGoodsTypes.size(), "DigitalGoods should have 8 subtypes");
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
        assertNotNull(result, "Should find ordinary payment type");
        assertEquals(TransactionType.Payment.ORDINARY, result, "Should be ORDINARY instance");
    }

    @Test
    public void findTransactionType_InvalidType_ReturnsNull() {
        // Arrange
        TransactionTypeRegistry registry = new TransactionTypeRegistry();

        // Act
        TransactionType result = registry.findTransactionType((byte) 99, (byte) 99);

        // Assert
        assertNull(result, "Should return null for unknown type");
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
        assertNull(result, "Should return null for unknown subtype");
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
        assertNotNull(result, "Should find escrow creation type");
        assertEquals(TransactionType.AdvancedPayment.ESCROW_CREATION, result, "Should be ESCROW_CREATION instance");
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
        assertNotNull(result, "toString should not return null");
        assertTrue(result.contains("TransactionTypeRegistry"), "Should contain class name");
        assertTrue(result.contains("registeredTypes="), "Should contain registeredTypes count");
    }
}