package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 改单结果。
 * 重复请求时 {@code duplicate=true}，返回首次处理的结果，不产生第二次占用调整。
 *
 * @param success  是否改单成功
 * @param duplicate 是否为幂等重复请求
 * @param orderId  订单内部号
 * @param status   改单后（或拒绝时）订单状态
 * @param price    成功时订单当前参考价；拒绝时为 null
 * @param quantity 成功时订单当前总数量；拒绝时为 null
 */
public record AmendOrderResult(boolean success, boolean duplicate, Long orderId,
                               OrderStatus status, Long price, Long quantity,
                               RejectReason rejectReason, String message) {
}
