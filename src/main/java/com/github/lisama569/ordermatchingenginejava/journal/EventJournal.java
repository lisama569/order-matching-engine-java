package com.github.lisama569.ordermatchingenginejava.journal;

import com.github.lisama569.ordermatchingenginejava.model.CancelRequest;
import com.github.lisama569.ordermatchingenginejava.model.MarketTick;
import com.github.lisama569.ordermatchingenginejava.model.OrderRequest;
import com.github.lisama569.ordermatchingenginejava.model.OrderStatus;
import com.github.lisama569.ordermatchingenginejava.model.OrderType;
import com.github.lisama569.ordermatchingenginejava.model.RejectReason;
import com.github.lisama569.ordermatchingenginejava.model.Side;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 追加式事件流水（WAL）。
 *
 * <p>所有「确定性输入」都先落盘再执行，重启后按行回放即可得到一致结论：
 * <ul>
 *   <li>{@code ACCOUNT}  开户/入金；</li>
 *   <li>{@code ORDER}    下单请求（含首次落盘时确定的 orderId / arrivalSeq / eventTime）；</li>
 *   <li>{@code CANCEL}   撤单请求（含 eventTime）；</li>
 *   <li>{@code TICK}     已接受的行情（只有通过序号校验的行情才落盘，断层行情无从落盘）；</li>
 *   <li>{@code SNAP}     簿记水位快照（arrivalSeq/tradeSeq），仅用于恢复计数器。</li>
 * </ul>
 * 拒单也会以 {@code ORDER} 落盘（status=REJECTED 带原因），因此重复请求在回放后
 * 仍能拿到首次结论；成交结果不单独落盘——它们是上述输入的确定性产物。
 *
 * <p>每行一条记录，字段以 TAB 分隔，{@code null} 写成空串。
 */
public class EventJournal {

    private static final String SEP = "\t";
    private static final String NULL = "";

    /** 回放读到的一条不可变事件。 */
    public record Event(String kind, String payload) {
        public String[] fields() {
            // -1 保留结尾空字段（null）
            return payload().split(SEP, -1);
        }
    }

    private final Path file;
    private volatile BufferedWriter writer;

    public EventJournal(Path directory) {
        try {
            Files.createDirectories(directory);
            this.file = directory.resolve("events.journal");
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open journal at " + directory, e);
        }
    }

    private synchronized void append(String kind, String... cols) {
        try {
            writer.write(kind);
            for (String c : cols) {
                writer.write(SEP);
                writer.write(c == null ? NULL : c);
            }
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("journal append failed", e);
        }
    }

    public synchronized void logAccount(String accountId, long initialCash) {
        append("ACCOUNT", accountId, Long.toString(initialCash));
    }

    public synchronized void logPosition(String accountId, String symbol, long qty) {
        append("POSITION", accountId, symbol, Long.toString(qty));
    }

    /** 最近价/序号水位（测试做市或外部补价用），保证回放后市价单保护价一致。 */
    public synchronized void logPrice(String symbol, long price, long nextSeq) {
        append("PRICE", symbol, Long.toString(price), Long.toString(nextSeq));
    }

    /**
     * 下单事件。status 为受理后的最终簿记状态（REJECTED 时 reason 必填）。
     */
    public synchronized void logOrder(OrderRequest req, String orderId, long arrivalSeq,
                                      long eventTime, OrderStatus status, RejectReason reason,
                                      long reservedAmount) {
        append("ORDER",
                nz(req.clientRequestId()), nz(req.accountId()), nz(req.symbol()),
                req.side() == null ? NULL : req.side().name(),
                req.type() == null ? NULL : req.type().name(),
                req.limitPrice() == null ? NULL : Long.toString(req.limitPrice()),
                Long.toString(req.orderQty()),
                nz(orderId), Long.toString(arrivalSeq), Long.toString(eventTime),
                status.name(), reason == null ? NULL : reason.name(),
                Long.toString(reservedAmount));
    }

    public synchronized void logCancel(CancelRequest req, long eventTime) {
        append("CANCEL", nz(req.clientRequestId()), nz(req.accountId()),
                nz(req.orderId()), Long.toString(eventTime));
    }

    public synchronized void logTick(MarketTick tick) {
        append("TICK", nz(tick.symbol()), Long.toString(tick.seq()),
                Long.toString(tick.price()), Long.toString(tick.eventTime()));
    }

    /** 定期记录计数器水位（回放时推进 arrivalSeq/tradeSeq 下界）。 */
    public synchronized void logCounters(long arrivalSeq, long tradeSeq) {
        append("SNAP", Long.toString(arrivalSeq), Long.toString(tradeSeq));
    }

    /** 启动时读取全部事件（按下盘先后顺序）。 */
    public List<Event> replayAll() {
        List<Event> events = new ArrayList<>();
        if (!Files.exists(file)) {
            return events;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                int idx = line.indexOf('\t');
                if (idx < 0) {
                    events.add(new Event(line, ""));
                } else {
                    events.add(new Event(line.substring(0, idx), line.substring(idx + 1)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("journal replay failed", e);
        }
        return events;
    }

    /** 仅供测试：清空流水。 */
    public synchronized void reset() {
        try {
            writer.close();
            Files.deleteIfExists(file);
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("journal reset failed", e);
        }
    }

    private static String nz(String s) {
        return s == null ? NULL : s;
    }

    // ---- 回放辅助解析 ----

    public static OrderRequest toOrderRequest(Event e) {
        String[] f = e.fields();
        Side side = f[3].isEmpty() ? null : Side.valueOf(f[3]);
        OrderType type = f[4].isEmpty() ? null : OrderType.valueOf(f[4]);
        return new OrderRequest(f[0], f[1], f[2], side, type,
                f[5].isEmpty() ? null : Long.parseLong(f[5]), Long.parseLong(f[6]));
    }

    public static String orderIdOf(Event e) {
        String[] f = e.fields();
        return f[7].isEmpty() ? null : f[7];
    }

    public static long arrivalSeqOf(Event e) {
        return Long.parseLong(e.fields()[8]);
    }

    public static long eventTimeOf(Event e) {
        return Long.parseLong(e.fields()[9]);
    }

    public static OrderStatus statusOf(Event e) {
        return OrderStatus.valueOf(e.fields()[10]);
    }

    public static RejectReason reasonOf(Event e) {
        String[] f = e.fields();
        return f[11].isEmpty() ? null : RejectReason.valueOf(f[11]);
    }

    public static long reservedAmountOf(Event e) {
        return Long.parseLong(e.fields()[12]);
    }

    public static CancelRequest toCancelRequest(Event e) {
        String[] f = e.fields();
        return new CancelRequest(f[0], f[1], f[2]);
    }

    public static long cancelEventTimeOf(Event e) {
        return Long.parseLong(e.fields()[3]);
    }

    public static MarketTick toTick(Event e) {
        String[] f = e.fields();
        return new MarketTick(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]),
                Long.parseLong(f[3]));
    }
}
