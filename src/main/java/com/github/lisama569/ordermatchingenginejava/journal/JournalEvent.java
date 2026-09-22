package com.github.lisama569.ordermatchingenginejava.journal;

import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.SubmitOrderRequest;

/**
 * 追加日志中的一条命令事件。重放时按原始顺序逐条重新执行。
 *
 * @param type   命令类型
 * @param submit SUBMIT 命令体
 * @param cancelAccountId CANCEL 的账户
 * @param cancelClientOrderId CANCEL 的客户订单号
 * @param tick   TICK 行情
 */
public record JournalEvent(EventType type,
                           SubmitOrderRequest submit,
                           String cancelAccountId,
                           String cancelClientOrderId,
                           MarketTick tick) {

    public static JournalEvent submit(SubmitOrderRequest req) {
        return new JournalEvent(EventType.SUBMIT, req, null, null, null);
    }

    public static JournalEvent cancel(String accountId, String clientOrderId) {
        return new JournalEvent(EventType.CANCEL, null, accountId, clientOrderId, null);
    }

    public static JournalEvent tick(MarketTick tick) {
        return new JournalEvent(EventType.TICK, null, null, null, tick);
    }
}
