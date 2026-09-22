package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 下单结果。
 * 重复请求时 {@code duplicate=true}，返回首次处理的结果，不产生任何额外占用/成交。
 */
public record SubmitOrderResult(boolean accepted, boolean duplicate, Long orderId,
                                OrderStatus status, RejectReason rejectReason, String message) {

    public static SubmitOrderResult rejected(RejectReason reason, String message) {
        return new SubmitOrderResult(false, false, null, OrderStatus.REJECTED, reason, message);
    }
}
