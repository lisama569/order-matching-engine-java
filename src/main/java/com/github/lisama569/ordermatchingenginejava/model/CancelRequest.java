package com.github.lisama569.ordermatchingenginejava.model;

/**
 * 撤单请求。
 *
 * @param clientRequestId 客户端幂等键：重复撤单返回首次结果，不重复释放占用
 * @param accountId       账户 ID（必须与订单归属一致）
 * @param orderId         待撤销订单号
 */
public record CancelRequest(String clientRequestId,
                            String accountId,
                            String orderId) {
}
