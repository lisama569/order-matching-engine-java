package com.github.lisama569.ordermatchingenginejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 全栈冒烟：开户 -> 下单成交 -> 幂等重复 -> 乱序行情被区分拒绝 -> 账户快照。
 */
@SpringBootTest(properties = {
        "matching.journal-dir=target/it-journal-${random.uuid}",
        "matching.feed-enabled=false",
        "matching.symbols[0]=600000"
})
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void endToEndOverHttp() throws Exception {
        // 开户
        mvc.perform(post("/api/accounts").param("accountId", "W1").param("initialCash", "5000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cash").value(5000000));
        mvc.perform(post("/api/accounts").param("accountId", "W2").param("initialCash", "0"))
                .andExpect(status().isOk());

        // 正常限价单（W1 买）
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"w-bid","accountId":"W1","symbol":"600000",
                 "side":"BUY","type":"LIMIT","limitPrice":9900,"orderQty":5}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        // 非法价格 -> 可区分拒绝
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"w-bad","accountId":"W1","symbol":"600000",
                 "side":"BUY","type":"LIMIT","limitPrice":0,"orderQty":5}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejectReason").value("INVALID_PRICE"));

        // 幂等重复：返回同一结论
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"w-bid","accountId":"W1","symbol":"600000",
                 "side":"BUY","type":"LIMIT","limitPrice":9900,"orderQty":5}
                """))
                .andExpect(jsonPath("$.duplicate").value(true));

        // 乱序行情：直接发 seq=2（期望1） -> TICK_GAP
        mvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON).content("""
                {"symbol":"600000","seq":2,"price":10000,"eventTime":1}
                """))
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejectReason").value("TICK_GAP"))
                .andExpect(jsonPath("$.expectedSeq").value(1));

        // 正确 seq=1 行情，9900 的买单与 MARKET 成交
        mvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON).content("""
                {"symbol":"600000","seq":1,"price":9800,"eventTime":2}
                """))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.trades[0].price").value(9900))
                .andExpect(jsonPath("$.trades[0].qty").value(5));

        // 账户快照：买方已付款
        mvc.perform(get("/api/accounts/W1"))
                .andExpect(jsonPath("$.cash").value(5000000 - 9900 * 5))
                .andExpect(jsonPath("$.reservedCash").value(0));

        // 期望序号推进
        mvc.perform(get("/api/ticks/expected/600000"))
                .andExpect(jsonPath("$.expectedSeq").value(2));
    }
}
