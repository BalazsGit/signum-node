package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.Block;
import application.module.brs.Blockchain;
import application.module.brs.SignumException;
import application.module.brs.services.ParameterService;
import application.module.brs.util.CollectionWithIndex;

import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ParameterParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.*;
import static application.module.brs.web.api.http.common.ResultFields.BLOCK_IDS_RESPONSE;
import static application.module.brs.web.api.http.common.ResultFields.NEXT_INDEX_RESPONSE;

public final class GetAccountBlockIds extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final Blockchain blockchain;

    public GetAccountBlockIds(ParameterService parameterService, Blockchain blockchain) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, ACCOUNT_PARAMETER, TIMESTAMP_PARAMETER,
                FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        Account account = parameterService.getAccount(req);

        int timestamp = ParameterParser.getTimestamp(req);
        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JsonArray blockIds = new JsonArray();
        CollectionWithIndex<Block> blocks = blockchain.getBlocks(account, timestamp, firstIndex, lastIndex);
        for (Block block : blocks) {
            blockIds.add(block.getStringId());
        }

        JsonObject response = new JsonObject();
        response.add(BLOCK_IDS_RESPONSE, blockIds);

        if (blocks.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, blocks.nextIndex());
        }

        return response;
    }

}
