package com.github.lisama569.ordermatchingenginejava.account;

/**
 * 账户某一标的维度的敞口快照（单位：现金为最小货币单位，持仓为最小数量单位）。
 *
 * @param cashBalance       现金总余额
 * @param cashReserved      买单冻结的现金（含费用，本实现无费用）
 * @param cashAvailable     可用现金 = 现金总余额 - 冻结
 * @param position          标的净持仓
 * @param positionReserved  卖单冻结的持仓
 * @param positionAvailable 可用持仓 = 持仓 - 冻结
 * @param exposure          单账户单标的敞口 = 买单冻结资金 + 卖单冻结持仓按保护价折算金额
 * @param exposureLimit     敞口上限
 */
public record AccountSnapshot(String accountId, String symbol,
                              long cashBalance, long cashReserved, long cashAvailable,
                              long position, long positionReserved, long positionAvailable,
                              long exposure, long exposureLimit) {
}
