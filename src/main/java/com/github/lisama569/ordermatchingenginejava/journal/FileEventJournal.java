package com.github.lisama569.ordermatchingenginejava.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
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
 * 基于本地 JSONL 文件的命令流水（WAL）。
 *
 * <p>语义：
 * <ul>
 *   <li>每条命令占一行 JSON，append 串行化并在返回前 flush 到磁盘；</li>
 *   <li>{@code readAll} 按写入顺序返回全部事件，供重启后确定性回放；</li>
 *   <li>回放阶段不再写日志（由 OrderService 控制），避免重复追加。</li>
 * </ul>
 */
public final class FileEventJournal implements EventJournal {

    private static final Logger log = LoggerFactory.getLogger(FileEventJournal.class);

    private final Path file;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Object writeLock = new Object();
    private long appended;

    public FileEventJournal(Path file) {
        this.file = file;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (Files.notExists(file)) {
                Files.createFile(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot init journal: " + file, e);
        }
    }

    @Override
    public void append(JournalEvent event) {
        synchronized (writeLock) {
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(mapper.writeValueAsString(event));
                w.newLine();
                w.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("journal append failed", e);
            }
            appended++;
        }
    }

    @Override
    public List<JournalEvent> readAll() {
        List<JournalEvent> events = new ArrayList<>();
        if (Files.notExists(file)) {
            return events;
        }
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    events.add(mapper.readValue(line, JournalEvent.class));
                } catch (IOException e) {
                    throw new UncheckedIOException("corrupt journal at line " + lineNo, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("journal read failed", e);
        }
        log.info("journal loaded: file={}, events={}", file, events.size());
        return events;
    }

    @Override
    public long size() {
        synchronized (writeLock) {
            return appended;
        }
    }
}
