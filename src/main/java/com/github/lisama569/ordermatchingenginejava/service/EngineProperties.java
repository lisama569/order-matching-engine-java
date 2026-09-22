package com.github.lisama569.ordermatchingenginejava.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 引擎可调参数（application.properties 中以 matching.* 配置）。
 *
 * @param journalDir       事件流水目录
 * @param exposureLimit    单账户单标的在簿敞口上限（分）
 * @param marketProtectNum 市价单保护价倍率分子
 * @param marketProtectDen 市价单保护价倍率分母（num/den，如 110/100 = +10%）
 * @param symbols          初始注册标的白名单
 * @param feedEnabled      是否启动进程内模拟行情定时推送
 * @param feedIntervalMs   模拟行情间隔（毫秒）
 */
@ConfigurationProperties(prefix = "matching")
public record EngineProperties(String journalDir,
                               long exposureLimit,
                               long marketProtectNum,
                               long marketProtectDen,
                               List<String> symbols,
                               boolean feedEnabled,
                               long feedIntervalMs) {

    public EngineProperties {
        if (journalDir == null || journalDir.isBlank()) {
            journalDir = "data/journal";
        }
        if (exposureLimit <= 0) {
            exposureLimit = 10_000_000L;
        }
        if (marketProtectNum <= 0) {
            marketProtectNum = 110L;
        }
        if (marketProtectDen <= 0) {
            marketProtectDen = 100L;
        }
        if (symbols == null) {
            symbols = List.of("600000", "BTCUSDT");
        }
        if (feedIntervalMs <= 0) {
            feedIntervalMs = 1000L;
        }
    }
}
