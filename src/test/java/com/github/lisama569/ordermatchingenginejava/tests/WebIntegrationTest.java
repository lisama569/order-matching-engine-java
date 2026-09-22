package com.github.lisama569.ordermatchingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 端到端：HTTP 状态码 + 可区分拒因 + 幂等标记 + 行情序号拒绝。
 * 注意：本测试依赖默认种子账户 alice/bob 与标的 AAPL，且与其他用例共享
 * target/test-data/journal.jsonl；断言只依赖拒因/标志，不依赖历史计数。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WebIntegrationTest.class);
    private static final ObjectMapper M = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private String orderJson(String cid, String account, String side, String type,
                             Long limitPrice, Long protectionPrice, long qty) throws Exception {
        var node = M.createObjectNode();
        node.put("clientOrderId", cid);
        node.put("accountId", account);
        node.put("symbol", "AAPL");
        node.put("side", side);
        node.put("type", type);
        if (limitPrice != null) {
            node.put("limitPrice", limitPrice);
        }
        if (protectionPrice != null) {
            node.put("protectionPrice", protectionPrice);
        }
        node.put("quantity", qty);
        return node.toString();
    }

    @Test
    void fullHttpFlow() throws Exception {
        // 非法价格 => 422 + INVALID_PRICE
        String badPrice = orderJson("web-bad-price", "alice", "BUY", "LIMIT", 0L, null, 10);
        String body = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(badPrice))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        JsonNode bad = M.readTree(body);
        log.info("[web] 非法价格响应: accepted={} reason={}", bad.get("accepted").asBoolean(),
                bad.get("rejectReason").asText());
        org.junit.jupiter.api.Assertions.assertEquals("INVALID_PRICE",
                bad.get("rejectReason").asText());

        // 正常限价买单（bob 有现金）
        String buy = orderJson("web-buy-1", "bob", "BUY", "LIMIT", 100L, null, 10);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(buy))
                .andExpect(status().isOk());

        // 重复提交 => 200 + duplicate=true
        String dupBody = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(buy))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode dup = M.readTree(dupBody);
        log.info("[web] 重复提交响应: duplicate={} orderId={}", dup.get("duplicate").asBoolean(),
                dup.get("orderId"));
        org.junit.jupiter.api.Assertions.assertTrue(dup.get("duplicate").asBoolean());

        // 占用查询：冻结 = 10*100 = 1000
        String exp = mvc.perform(get("/api/accounts/bob/symbols/AAPL/exposure"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode exposure = M.readTree(exp);
        log.info("[web] bob 占用: cashReserved={} exposure={}",
                exposure.get("cashReserved").asLong(), exposure.get("exposure").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(1000, exposure.get("cashReserved").asLong());

        // 卖单（alice 持有 AAPL 种子持仓）
        String sell = orderJson("web-sell-1", "alice", "SELL", "LIMIT", 100L, null, 10);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(sell))
                .andExpect(status().isOk());

        // 断层行情 seq=999999 => 409 + TICK_SEQUENCE_GAP
        String gap = M.createObjectNode().put("seq", 999_999).put("symbol", "AAPL").put("price", 100).toString();
        String gapResp = mvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(gap))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        log.info("[web] 断层行情响应: reason={}", M.readTree(gapResp).get("rejectReason").asText());
        org.junit.jupiter.api.Assertions.assertEquals("TICK_SEQUENCE_GAP",
                M.readTree(gapResp).get("rejectReason").asText());

        // 撤单成功
        String cancel = M.createObjectNode().put("accountId", "bob").put("clientOrderId", "web-buy-1").toString();
        mvc.perform(post("/api/orders/cancel").contentType(MediaType.APPLICATION_JSON).content(cancel))
                .andExpect(status().isOk());
        // 重复撤单 => 200 + duplicate=true
        String cancelDup = mvc.perform(post("/api/orders/cancel").contentType(MediaType.APPLICATION_JSON).content(cancel))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        log.info("[web] 重复撤单: duplicate={}", M.readTree(cancelDup).get("duplicate").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(M.readTree(cancelDup).get("duplicate").asBoolean());
    }
}
