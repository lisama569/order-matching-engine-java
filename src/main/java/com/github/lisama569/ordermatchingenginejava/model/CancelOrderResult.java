package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 撤单结果。重复撤单请求返回 {@code duplicate=true} 的幂等成功结果。
 */
public record CancelOrderResult(boolean success, boolean duplicate, Long orderId,
                                OrderStatus status, RejectReason rejectReason, String message) {
}
