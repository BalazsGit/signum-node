package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.GOODS_NOT_DELIVERED;
import static application.module.node.web.api.http.common.JSONResponses.INCORRECT_PURCHASE;
import static application.module.node.web.api.http.common.Parameters.PURCHASE_PARAMETER;

public final class DGSFeedback extends CreateTransaction {

    private final ParameterService parameterService;
    private final AccountService accountService;
    private final Blockchain blockchain;

    public DGSFeedback(ParameterService parameterService, Blockchain blockchain, AccountService accountService,
            APITransactionManager apiTransactionManager) {
        super(new LegacyDocTag[] { LegacyDocTag.DGS, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager,
                PURCHASE_PARAMETER);
        this.parameterService = parameterService;
        this.accountService = accountService;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        DigitalGoodsStore.Purchase purchase = parameterService.getPurchase(req);
        Account buyerAccount = parameterService.getSenderAccount(req);

        if (buyerAccount.getId() != purchase.getBuyerId()) {
            return INCORRECT_PURCHASE;
        }
        if (purchase.getEncryptedGoods() == null) {
            return GOODS_NOT_DELIVERED;
        }

        Account sellerAccount = accountService.getAccount(purchase.getSellerId());
        Attachment attachment = new Attachment.DigitalGoodsFeedback(purchase.getId(), blockchain.getHeight());

        return createTransaction(req, buyerAccount, sellerAccount.getId(), 0, attachment);
    }

}
