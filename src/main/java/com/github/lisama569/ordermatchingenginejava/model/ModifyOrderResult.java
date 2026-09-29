package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 改单结果。与下单结果分离，拒因不会与下单失败混淆。
 * 重复改单请求返回 {@code duplicate=true} 的首次结果。
 *
 * @param success     是否改单成功（失败时订单价格/数量/占用保持原样）
 * @param duplicate   是否为同一 amendId 的重复提交
 * @param orderId     内部订单号（订单不存在时为 null）
 * @param status      改单后（或首次处理时）的订单状态
 * @param price       改单后的有效参考价（失败时为订单当前价）
 * @param quantity    改单后的有效订单总数量（失败时为订单当前数量）
 * @param rejectReason 可区分的拒绝原因
 * @param message     说明
 */
public record ModifyOrderResult(boolean success, boolean duplicate, Long orderId,
                                OrderStatus status, long price, long quantity,
                                RejectReason rejectReason, String message) {
}
