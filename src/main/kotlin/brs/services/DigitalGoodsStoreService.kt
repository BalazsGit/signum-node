package brs.services

import brs.entity.Goods
import brs.entity.Purchase
import brs.entity.Transaction
import brs.transaction.appendix.Appendix
import brs.transaction.appendix.Attachment

interface DigitalGoodsStoreService {
    enum class Event {
        GOODS_LISTED, GOODS_DELISTED, GOODS_PRICE_CHANGE, GOODS_QUANTITY_CHANGE,
        PURCHASE, DELIVERY, REFUND, FEEDBACK
    }

    fun addGoodsListener(listener: (Goods) -> Unit, eventType: Event)

    fun addPurchaseListener(listener: (Purchase) -> Unit, eventType: Event)

    fun getGoods(goodsId: Long): Goods?

    fun getAllGoods(from: Int, to: Int): Collection<Goods>

    fun getGoodsInStock(from: Int, to: Int): Collection<Goods>

    fun getSellerGoods(sellerId: Long, inStockOnly: Boolean, from: Int, to: Int): Collection<Goods>

    fun getAllPurchases(from: Int, to: Int): Collection<Purchase>

    fun getSellerPurchases(sellerId: Long, from: Int, to: Int): Collection<Purchase>

    fun getBuyerPurchases(buyerId: Long, from: Int, to: Int): Collection<Purchase>

    fun getSellerBuyerPurchases(sellerId: Long, buyerId: Long, from: Int, to: Int): Collection<Purchase>

    fun getPendingSellerPurchases(sellerId: Long, from: Int, to: Int): Collection<Purchase>

    fun getPurchase(purchaseId: Long): Purchase?

    fun changeQuantity(goodsId: Long, deltaQuantity: Int, allowDelisted: Boolean)

    fun purchase(transaction: Transaction, attachment: Attachment.DigitalGoodsPurchase)

    fun addPurchase(transaction: Transaction, attachment: Attachment.DigitalGoodsPurchase, sellerId: Long)

    fun listGoods(transaction: Transaction, attachment: Attachment.DigitalGoodsListing)

    fun delistGoods(goodsId: Long)

    fun feedback(purchaseId: Long, encryptedMessage: Appendix.EncryptedMessage?, message: Appendix.Message?)

    fun refund(sellerId: Long, purchaseId: Long, refundPlanck: Long, encryptedMessage: Appendix.EncryptedMessage?)

    fun getExpiredPendingPurchases(timestamp: Int): Collection<Purchase>

    fun changePrice(goodsId: Long, pricePlanck: Long)

    fun deliver(transaction: Transaction, attachment: Attachment.DigitalGoodsDelivery)

    fun getPendingPurchase(purchaseId: Long): Purchase?

    fun setPending(purchase: Purchase, pendingValue: Boolean)
}
