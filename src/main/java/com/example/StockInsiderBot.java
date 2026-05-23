package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class StockInsiderBot {

    private static final String SEC_BASE = "https://www.sec.gov/Archives/";
    private static final String TICKER_URL = "https://www.sec.gov/include/ticker.txt";
    private static final String DEFAULT_SEC_USER_AGENT = "SEC4-Insider-Bot AdminContact@example.com";
    private static final String DEFAULT_SEC_CONTACT_EMAIL = "contact@example.com";
    private static final long DEFAULT_MINIMUM_USD = 500_000L;
    private static final int DEFAULT_MAX_LOOKBACK_DAYS = 1;
    private static final boolean DEFAULT_DEBUG = true;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static final Map<String, String> FALLBACK_TICKER_MAP = Map.ofEntries(
            Map.entry("BRKB", "1067983"),
            Map.entry("BRK-B", "1067983"),
            Map.entry("MSFT", "0000789019"),
            Map.entry("ZTS", "0001555285"),
            Map.entry("STZ", "0001593873")
    );

    private static final Map<String, String> POSITION_TRANSLATIONS = new LinkedHashMap<>();

    static {
        POSITION_TRANSLATIONS.put("chief executive officer", "首席执行官");
        POSITION_TRANSLATIONS.put("ceo", "首席执行官");
        POSITION_TRANSLATIONS.put("president", "总裁");
        POSITION_TRANSLATIONS.put("executive vice president", "执行副总裁");
        POSITION_TRANSLATIONS.put("evp", "执行副总裁");
        POSITION_TRANSLATIONS.put("senior vice president", "高级副总裁");
        POSITION_TRANSLATIONS.put("svp", "高级副总裁");
        POSITION_TRANSLATIONS.put("vice president", "副总裁");
        POSITION_TRANSLATIONS.put("vp", "副总裁");
        POSITION_TRANSLATIONS.put("chief financial officer", "首席财务官");
        POSITION_TRANSLATIONS.put("cfo", "首席财务官");
        POSITION_TRANSLATIONS.put("chief operating officer", "首席运营官");
        POSITION_TRANSLATIONS.put("coo", "首席运营官");
        POSITION_TRANSLATIONS.put("chief technology officer", "首席技术官");
        POSITION_TRANSLATIONS.put("cto", "首席技术官");
        POSITION_TRANSLATIONS.put("chief information officer", "首席信息官");
        POSITION_TRANSLATIONS.put("cio", "首席信息官");
        POSITION_TRANSLATIONS.put("general counsel", "总法律顾问");
        POSITION_TRANSLATIONS.put("director", "董事");
        POSITION_TRANSLATIONS.put("board member", "董事会成员");
        POSITION_TRANSLATIONS.put("treasurer", "财务主管");
        POSITION_TRANSLATIONS.put("secretary", "秘书");
        POSITION_TRANSLATIONS.put("controller", "财务总监");
        POSITION_TRANSLATIONS.put("chairman", "董事长");
        POSITION_TRANSLATIONS.put("vice chairman", "副董事长");
        POSITION_TRANSLATIONS.put("founder", "创始人");
        POSITION_TRANSLATIONS.put("co-founder", "联合创始人");
        POSITION_TRANSLATIONS.put("manager", "经理");
        POSITION_TRANSLATIONS.put("senior manager", "高级经理");
    }

    private static boolean debugEnabled = DEFAULT_DEBUG;

    // ==================== 主方法 ====================

    public static void main(String[] args) {
        try {
            // 解析参数和配置
            Map<String, String> options = parseOptions(args);
            String tickersArg = firstNonBlank(options.get("tickers"), System.getenv("TICKERS"), options.get("positional"));
            long minimumUsd = parseLong(firstNonBlank(options.get("threshold"), System.getenv("THRESHOLD_USD")), DEFAULT_MINIMUM_USD);
            int maxLookbackDays = parseInt(firstNonBlank(options.get("lookback"), System.getenv("LOOKBACK_DAYS")), DEFAULT_MAX_LOOKBACK_DAYS);
            boolean debug = parseBoolean(firstNonBlank(options.get("debug"), System.getenv("DEBUG")), DEFAULT_DEBUG);

            setDebug(debug);
            logDebug("Debug mode: " + debug);

            if (tickersArg == null || tickersArg.isBlank()) {
                String msg = buildConfigNotification("未提供股票代码", "请使用 --tickers=MSFT,AAPL 或配置 TICKERS 环境变量。");
                System.out.println(msg);
                sendNotification(msg);
                return;
            }

            String[] tickers = parseTickers(tickersArg);
            if (tickers.length == 0) {
                String msg = buildConfigNotification("股票代码无效", "未解析到有效股票代码。");
                System.out.println(msg);
                sendNotification(msg);
                return;
            }

            Map<String, String> tickerToCik = downloadTickerMapping();
            if (tickerToCik.isEmpty()) {
                String msg = buildErrorNotification("下载 SEC ticker mapping 失败，且 fallback 映射表为空。");
                System.err.println(msg);
                sendErrorNotification(msg);
                return;
            }

            Map<String, String> cikToRequestedTicker = new HashMap<>();
            Set<String> ciks = new HashSet<>();
            List<String> unmappedTickers = new ArrayList<>();

            for (String ticker : tickers) {
                String cik = findCikForTicker(ticker, tickerToCik);
                if (cik != null) {
                    String normalizedCik = cik.replaceFirst("^0+(?!$)", "");
                    ciks.add(normalizedCik);
                    cikToRequestedTicker.put(normalizedCik, ticker);
                    logDebug("Ticker mapped: " + ticker + " -> " + normalizedCik);
                } else {
                    unmappedTickers.add(ticker);
                    System.err.println("Warning: ticker not found: " + ticker);
                }
            }

            if (ciks.isEmpty()) {
                String msg = buildNoValidCikNotification(tickers, unmappedTickers);
                System.err.println(msg);
                sendNotification(msg);
                return;
            }

            LocalDate currentDate = LocalDate.now(ZoneId.of("America/New_York"));
            List<String> form4Urls = new ArrayList<>();

            MasterIndex masterIndex = findMasterIndex(currentDate, maxLookbackDays);
            if (masterIndex != null) {
                form4Urls.addAll(parseMasterIdx(masterIndex.content, ciks));
            }

            if (form4Urls.isEmpty()) {
                form4Urls.addAll(fetchForm4UrlsFromEdgarBrowse(ciks, maxLookbackDays));
            }

            if (form4Urls.isEmpty()) {
                String msg = buildMissingNotification(tickers, unmappedTickers, maxLookbackDays, minimumUsd);
                System.out.println(msg);
                sendNotification(msg);
                return;
            }

            // 解析 Form4
            Map<String, List<AlertEntry>> allAlerts = new LinkedHashMap<>();
            Set<String> tickersWithForm4 = new LinkedHashSet<>();
            int processedCount = 0;
            int failedCount = 0;

            for (String url : form4Urls) {
                try {
                    String xml = downloadText(url);
                    Map<String, List<AlertEntry>> parsed = parseForm4(xml, minimumUsd, cikToRequestedTicker);
                    parsed.forEach((ticker, alerts) -> {
                        tickersWithForm4.add(ticker);
                        if (!alerts.isEmpty()) {
                            allAlerts.computeIfAbsent(ticker, k -> new ArrayList<>()).addAll(alerts);
                        }
                    });
                    processedCount++;
                } catch (Exception ex) {
                    failedCount++;
                }
            }

            Map<String, List<AlertEntry>> filteredAlerts = new LinkedHashMap<>();
            for (String ticker : tickers) {
                if (allAlerts.containsKey(ticker) && !allAlerts.get(ticker).isEmpty()) {
                    filteredAlerts.put(ticker, allAlerts.get(ticker));
                }
            }

            if (filteredAlerts.isEmpty()) {
                String noTradeMsg = buildNoAlertNotification(tickers, unmappedTickers, minimumUsd, maxLookbackDays, processedCount, failedCount, tickersWithForm4);
                System.out.println(noTradeMsg);
                sendNotification(noTradeMsg);
                return;
            }

            String message = buildGroupedNotificationOptimized(filteredAlerts, masterIndex != null ? masterIndex.indexDate : currentDate.format(DateTimeFormatter.BASIC_ISO_DATE), minimumUsd, maxLookbackDays, processedCount, failedCount, unmappedTickers);
            sendNotification(message);

        } catch (Exception e) {
            sendErrorNotification(buildErrorNotification(e.getMessage()));
        }
    }

    // ==================== 优化后的钉钉消息生成 ====================

    private static String buildGroupedNotificationOptimized(Map<String, List<AlertEntry>> alertsByTicker, String indexDate, long minimumUsd, int lookbackDays, int processedCount, int failedCount, List<String> unmappedTickers) {
        StringBuilder msg = new StringBuilder();
        msg.append("### 🔔 内部人交易警报\n\n");
        msg.append("**报告日期**：").append(formatDate(indexDate)).append("  |  ");
        msg.append("**扫描天数**：").append(lookbackDays).append("  |  ");
        msg.append("**提醒阈值**：").append(formatAmount(minimumUsd)).append("\n");
        msg.append("**处理 Form4**：").append(processedCount).append("  |  ");
        msg.append("**失败**：").append(failedCount).append("\n");

        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠ 未映射股票：").append(String.join(", ", unmappedTickers)).append("\n");
        }

        msg.append("\n");

        for (Map.Entry<String, List<AlertEntry>> entry : alertsByTicker.entrySet()) {
            String ticker = entry.getKey();
            List<AlertEntry> transactions = entry.getValue();
            msg.append("---\n");
            msg.append("## ").append(ticker).append("  |  笔数: ").append(transactions.size()).append("\n");

            for (AlertEntry e : transactions) {
                boolean isBuy = "BUY".equals(e.type);
                msg.append(isBuy ? "🔴 买入 " : "🟢 卖出 ");
                msg.append(formatAmount(e.amount)).append("\n");
                msg.append("> 日期：").append(formatDate(e.transactionDate))
                   .append("  |  人员：").append(safeText(e.ownerName, "Unknown"))
                   .append("  |  职位：").append(translatePosition(e.position))
                   .append("  |  数量：").append(formatNumber(e.shares))
                   .append(" 股  |  价格：$").append(String.format("%,.2f", e.price))
                   .append("  |  持股：").append(e.sharesOwnedAfter > 0 ? formatNumber(e.sharesOwnedAfter) : "N/A")
                   .append(e.security != null && !e.security.equalsIgnoreCase("stock") ? "  |  证券类型：" + e.security : "")
                   .append("\n");
            }
            msg.append("\n");
        }

        msg.append("说明：P = Purchase 买入，S = Sale 卖出；10b5-1 表示预设交易计划。");
        return msg.toString().trim();
    }

    // ==================== 其余方法保持原逻辑 ====================
    // downloadText, parseForm4, parseMasterIdx, fetchForm4UrlsFromEdgarBrowse, etc. 保持原有逻辑

    // 其他方法省略，但和你原版完全兼容，例如: parseOptions, firstNonBlank, setDebug, logDebug, parseLong, parseInt, parseBoolean, parseTickers, translatePosition, formatDate, formatNumber, formatAmount, safeText, sendNotification, sendDingTalkWebhook, buildDingTalkUrl
}
