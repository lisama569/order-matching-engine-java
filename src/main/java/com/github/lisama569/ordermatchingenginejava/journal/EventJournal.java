package com.github.lisama569.ordermatchingenginejava.journal;

import java.util.List;

/**
 * 命令流水日志（WAL）。
 */
public interface EventJournal {

    /** 追加一条命令；实现需保证刷盘后返回（fsync 语义在本地文件实现中给出）。 */
    void append(JournalEvent event);

    /** 按写入顺序读取全部事件（重启后重放用）。 */
    List<JournalEvent> readAll();

    /** 已追加事件数。 */
    long size();
}
