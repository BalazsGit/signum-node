package application.module.node.web.api.http.handler;

import application.module.node.Block;
import application.module.node.Blockchain;
import application.module.node.Generator;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.services.BlockService;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ResultFields;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

public final class GetMiningInfo extends ApiServlet.JsonRequestHandler {

    private final Blockchain blockchain;
    private final BlockService blockService;
    private final Generator generator;
    private final PropertyService propertyService;

    public GetMiningInfo(Blockchain blockchain, BlockService blockService, Generator generator,
            PropertyService propertyService) {
        super(new LegacyDocTag[] { LegacyDocTag.MINING, LegacyDocTag.INFO });
        this.blockchain = blockchain;
        this.blockService = blockService;
        this.generator = generator;
        this.propertyService = propertyService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {
        JsonObject response = new JsonObject();

        response.addProperty(ResultFields.HEIGHT_RESPONSE,
                Long.toString((long) blockchain.getHeight() + 1));

        Block lastBlock = blockchain.getLastBlock();
        byte[] newGenSig = generator.calculateGenerationSignature(lastBlock.getGenerationSignature(),
                lastBlock.getGeneratorId());

        response.addProperty(ResultFields.GENERATION_SIGNATURE_RESPONSE, Convert.toHexString(newGenSig));
        response.addProperty(ResultFields.BASE_TARGET_RESPONSE, Long.toString(lastBlock.getCapacityBaseTarget()));
        response.addProperty(ResultFields.AVERAGE_COMMITMENT_NQT_RESPONSE,
                Long.toString(lastBlock.getAverageCommitment()));
        response.addProperty(ResultFields.LAST_BLOCK_REWARD_RESPONSE,
                Long.toString(blockService.getBlockReward(lastBlock)
                        / propertyService.getInt(Props.ONE_COIN_NQT)));
        response.addProperty(ResultFields.LAST_BLOCK_REWARD_NQT_RESPONSE,
                Long.toString(blockService.getBlockReward(lastBlock)));
        response.addProperty(ResultFields.TIMESTAMP_RESPONSE, Long.toString((long) lastBlock.getTimestamp()));

        return response;
    }
}
