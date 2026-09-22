package com.github.lisama569.ordermatchingenginejava.model;

import java.util.List;

/**
 * 行情处理结果：是否接受、拒因以及该行情触发的成交。
 * 重复/乱序/断层的行情被拒绝而非静默丢弃。
 */
public record TickResult(boolean accepted, boolean duplicate, long expectedSeq, long receivedSeq,
                         RejectReason rejectReason, List<Fill> fills) {
}
