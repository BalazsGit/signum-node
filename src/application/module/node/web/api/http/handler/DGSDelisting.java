package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.UNKNOWN_GOODS;
import static application.module.node.web.api.http.common.Parameters.GOODS_PARAMETER;

public final class DGSDelisting extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;

    public DGSDelisting(ParameterService parameterService, Blockchain blockchain,
            APITransactionManager apiTransactionManager, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.DGS, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager, fluxCapacitor,
                GOODS_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        Account account = parameterService.getSenderAccount(req);
        DigitalGoodsStore.Goods goods = parameterService.getGoods(req);
        if (goods.isDelisted() || goods.getSellerId() != account.getId()) {
            return UNKNOWN_GOODS;
        }
        Attachment attachment = new Attachment.DigitalGoodsDelisting(fluxCapacitor, goods.getId(), blockchain.getHeight());
        return createTransaction(req, account, attachment);
    }

}