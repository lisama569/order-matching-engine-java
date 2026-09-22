package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 撤单结果。
 *
 * @param success         是否撤销成功
 * @param duplicate       是否为重复请求（返回首次结论）
 * @param clientRequestId 幂等键
 * @param orderId         订单号
 * @param cancelledQty    本次撤销的剩余数量
 * @param releasedCash    释放的预占资金（分，买单）
 * @param releasedPosition 释放的冻结持仓（股，卖单）
 * @param rejectReason    失败原因（ORDER_NOT_FOUND / ORDER_NOT_CANCELLABLE）
 */
public record CancelResponse(boolean success,
                             boolean duplicate,
                             String clientRequestId,
                             String orderId,
                             long cancelledQty,
                             long releasedCash,
                             long releasedPosition,
                             RejectReason rejectReason) {

    public static CancelResponse ok(boolean duplicate, String clientRequestId, String orderId,
                                    long cancelledQty, long releasedCash, long releasedPosition) {
        return new CancelResponse(true, duplicate, clientRequestId, orderId, cancelledQty,
                releasedCash, releasedPosition, null);
    }

    public static CancelResponse fail(boolean duplicate, String clientRequestId, String orderId,
                                      RejectReason reason) {
        return new CancelResponse(false, duplicate, clientRequestId, orderId, 0L, 0L, 0L, reason);
    }
}
