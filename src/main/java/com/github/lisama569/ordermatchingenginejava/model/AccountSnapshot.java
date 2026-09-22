package com.github.lisama569.ordermatchingenginejava.model;

import java.util.Map;

/**
 * 账户快照（用于查询与守恒校验）。
 *
 * <p>守恒关系：
 * <ul>
 *   <li>cash = availableCash + reservedCash</li>
 *   <li>对任一标的：position = availablePosition + reservedPosition</li>
 * </ul>
 *
 * @param accountId         账户
 * @param cash              现金总额（分）
 * @param reservedCash      买单预占资金（分）
 * @param availableCash     可用资金（分）
 * @param positions         持仓（股），symbol -> 总持仓
 * @param reservedPositions 冻结持仓（股），symbol -> 冻结量
 * @param openExposure      单标的敞口（分），symbol -> 敞口金额
 */
public record AccountSnapshot(String accountId,
                              long cash,
                              long reservedCash,
                              long availableCash,
                              Map<String, Long> positions,
                              Map<String, Long> reservedPositions,
                              Map<String, Long> openExposure) {
}
