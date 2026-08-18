package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.UNKNOWN_ORDER;
import static application.module.node.web.api.http.common.Parameters.ORDER_PARAMETER;

public final class CancelBidOrder extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;
    private final AssetExchange assetExchange;

    public CancelBidOrder(ParameterService parameterService, Blockchain blockchain, AssetExchange assetExchange,
            APITransactionManager apiTransactionManager, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.AE, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager, fluxCapacitor,
                ORDER_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        long orderId = ParameterParser.getOrderId(req);
        Account account = parameterService.getSenderAccount(req);
        Order.Bid orderData = assetExchange.getBidOrder(orderId);
        if (orderData == null || orderData.getAccountId() != account.getId()) {
            return UNKNOWN_ORDER;
        }
        Attachment attachment = new Attachment.ColoredCoinsBidOrderCancellation(fluxCapacitor, orderId, blockchain.getHeight());
        return createTransaction(req, account, attachment);
    }

}