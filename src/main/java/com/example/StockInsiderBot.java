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

    public static void main(String[] args) {
        try {
            Map<String, String> options = parseOptions(args);

            String tickersArg = firstNonBlank(
                    options.get("tickers"),
                    System.getenv("TICKERS"),
                    options.get("positional")
            );

            long minimumUsd = parseLong(
                    firstNonBlank(options.get("threshold"), System.getenv("THRESHOLD_USD")),
                    DEFAULT_MINIMUM_USD
            );

            int maxLookbackDays = parseInt(
                    firstNonBlank(options.get("lookback"), System.getenv("LOOKBACK_DAYS")),
                    DEFAULT_MAX_LOOKBACK_DAYS
            );

            boolean debug = parseBoolean(
                    firstNonBlank(options.get("debug"), System.getenv("DEBUG")),
                    DEFAULT_DEBUG
            );

            setDebug(debug);
            logDebug("Debug mode enabled: " + debug);
            logDebug("Tickers: " + tickersArg);
            logDebug("Threshold: " + minimumUsd);
            logDebug("Lookback days: " + maxLookbackDays);

            if (tickersArg == null || tickersArg.isBlank()) {
                String msg = buildConfigNotification(
                        "未提供股票代码",
                        "请使用 --tickers=MSFT,AAPL 或配置 TICKERS 环境变量。"
                );
                System.out.println(msg);
                sendNotification(msg);
                return;
            }

            String[] tickers = parseTickers(tickersArg);
            if (tickers.length == 0) {
                String msg = buildConfigNotification(
                        "股票代码无效",
                        "输入内容中没有解析到有效股票代码。"
                );
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
                    System.err.println("Warning: ticker not found in SEC mapping: " + ticker);
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
                logDebug("Master index lookup returned " + form4Urls.size() + " Form 4 URLs.");
            } else {
                logDebug("Unable to find a valid SEC master index in the last " + maxLookbackDays + " days.");
            }

            if (form4Urls.isEmpty()) {
                logDebug("No Form 4 URLs in master index. Falling back to SEC browse API...");
                form4Urls.addAll(fetchForm4UrlsFromEdgarBrowse(ciks, maxLookbackDays));
                logDebug("Browse API fallback returned " + form4Urls.size() + " Form 4 XML URLs.");
            }

            if (form4Urls.isEmpty()) {
                String msg = buildMissingNotification(tickers, unmappedTickers, maxLookbackDays, minimumUsd);
                System.out.println(msg);
                sendNotification(msg);
                return;
            }

            Map<String, List<AlertEntry>> allAlerts = new LinkedHashMap<>();
            Set<String> tickersWithForm4 = new LinkedHashSet<>();
            int processedCount = 0;
            int failedCount = 0;

            for (String url : form4Urls) {
                try {
                    String xml = downloadText(url);
                    logDebug("Processing Form 4 URL: " + url);

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
                    System.err.println("Warning: failed to process Form 4 at " + url + " - " + ex.getMessage());
                }
            }

            if (processedCount == 0 && failedCount > 0) {
                throw new Exception("已找到 " + failedCount + " 份 Form 4，但全部处理失败。请检查 SEC 返回内容或 XML 解析逻辑。");
            }

            Map<String, List<AlertEntry>> filteredAlerts = new LinkedHashMap<>();
            for (String ticker : tickers) {
                if (allAlerts.containsKey(ticker) && !allAlerts.get(ticker).isEmpty()) {
                    filteredAlerts.put(ticker, allAlerts.get(ticker));
                }
            }

            if (filteredAlerts.isEmpty()) {
                String noTradeMsg = buildNoAlertNotification(
                        tickers,
                        unmappedTickers,
                        minimumUsd,
                        maxLookbackDays,
                        processedCount,
                        failedCount,
                        tickersWithForm4
                );
                System.out.println(noTradeMsg);
                sendNotification(noTradeMsg);
                return;
            }

            String message = buildGroupedNotification(
                    filteredAlerts,
                    masterIndex != null ? masterIndex.indexDate : currentDate.format(DateTimeFormatter.BASIC_ISO_DATE),
                    minimumUsd,
                    maxLookbackDays,
                    processedCount,
                    failedCount,
                    unmappedTickers
            );

            boolean notified = sendNotification(message);
            int alertCount = filteredAlerts.values().stream().mapToInt(List::size).sum();

            System.out.println("Found " + alertCount + " alert(s) in " + filteredAlerts.size()
                    + " ticker(s). Notification sent: " + notified);

            if (!notified) {
                System.out.println(message);
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();

            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (!errorMsg.contains("No Form 4 filings found")
                    && !errorMsg.contains("No large insider transactions found")
                    && !errorMsg.contains("No valid CIKs found")) {
                sendErrorNotification(buildErrorNotification(errorMsg));
            }
            System.exit(1);
        }
    }

    private static class AlertEntry {
        final String ownerName;
        final String position;
        final String type;
        final String security;
        final long shares;
        final double price;
        final double amount;
        final boolean is10b51;
        final String transactionDate;
        final long sharesOwnedAfter;

        AlertEntry(String ownerName, String position, String type, String security,
                   long shares, double price, double amount, boolean is10b51,
                   String transactionDate, long sharesOwnedAfter) {
            this.ownerName = ownerName;
            this.position = position;
            this.type = type;
            this.security = security;
            this.shares = shares;
            this.price = price;
            this.amount = amount;
            this.is10b51 = is10b51;
            this.transactionDate = transactionDate;
            this.sharesOwnedAfter = sharesOwnedAfter;
        }
    }

    private static class MasterIndex {
        final String indexDate;
        final String content;

        MasterIndex(String indexDate, String content) {
            this.indexDate = indexDate;
            this.content = content;
        }
    }

    // ==================== 通知构建 ====================
    private static String buildGroupedNotification(
            Map<String, List<AlertEntry>> alertsByTicker,
            String indexDate,
            long minimumUsd,
            int lookbackDays,
            int processedCount,
            int failedCount,
            List<String> unmappedTickers) {
    
        final String BR = "  \n";
    
        int tickerCount = alertsByTicker.size();
        int tradeCount = alertsByTicker.values().stream().mapToInt(List::size).sum();
        double totalAmount = alertsByTicker.values().stream()
                .flatMap(List::stream)
                .mapToDouble(e -> e.amount)
                .sum();
    
        StringBuilder msg = new StringBuilder();
    
        msg.append("📅 报告日期：").append(formatDate(indexDate)).append(BR);
        msg.append("🔎 扫描范围：最近 ").append(lookbackDays).append(" 天").append(BR);
        msg.append("💰 提醒规则：买入不限金额，卖出 ≥ ").append(formatAmount(minimumUsd)).append(BR);
        msg.append("📄 已处理 Form 4：").append(processedCount).append(" 份").append(BR);
    
        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append(" 份").append(BR);
        }
    
        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append(BR);
        }
    
        msg.append("📊 命中结果：")
                .append(tickerCount).append(" 个股票，")
                .append(tradeCount).append(" 笔交易，合计 ")
                .append(formatAmount(totalAmount))
                .append("\n\n");
    
        for (Map.Entry<String, List<AlertEntry>> entry : alertsByTicker.entrySet()) {
            String ticker = entry.getKey();
            List<AlertEntry> entries = new ArrayList<>(entry.getValue());
    
            entries.sort(Comparator
                    .comparing((AlertEntry e) -> "BUY".equals(e.type) ? 0 : 1)
                    .thenComparing((AlertEntry e) -> -e.amount));
    
            long buyCount = entries.stream().filter(e -> "BUY".equals(e.type)).count();
            long sellCount = entries.stream().filter(e -> "SELL".equals(e.type)).count();
            double tickerAmount = entries.stream().mapToDouble(e -> e.amount).sum();
    
            msg.append("## ").append(ticker).append("\n");
            msg.append("合计：")
                    .append(entries.size()).append(" 笔，")
                    .append(formatAmount(tickerAmount))
                    .append(" ｜ 买入 ").append(buyCount)
                    .append(" ｜ 卖出 ").append(sellCount)
                    .append(BR);
    
            for (AlertEntry e : entries) {
                boolean isBuy = "BUY".equals(e.type);
    
                String actionText = isBuy ? "买入" : "卖出";
                String actionIcon = isBuy ? "🔴" : "🟢";
                String date = e.transactionDate == null || e.transactionDate.isBlank()
                        ? "N/A"
                        : formatDate(e.transactionDate);
    
                String sharesStr = formatNumber(e.shares);
                String amountStr = formatAmount(e.amount);
                String priceStr = "$" + String.format("%,.2f", e.price);
                String ownedAfter = e.sharesOwnedAfter > 0 ? formatNumber(e.sharesOwnedAfter) : "N/A";
                String position = translatePosition(e.position);
    
                msg.append(actionIcon)
                        .append(" **").append(actionText)
                        .append(" ").append(amountStr)
                        .append("**");
    
                if (e.is10b51) {
                    msg.append(" `10b5-1计划交易`");
                }
    
                msg.append(BR);
                msg.append("日期：").append(date).append(BR);
                msg.append("人员：").append(safeText(e.ownerName, "Unknown Owner")).append(BR);
                msg.append("职位：").append(position).append(BR);
                msg.append("数量：").append(sharesStr).append(" 股 @ ").append(priceStr).append(BR);
                msg.append("交易后持股：").append(ownedAfter).append(BR);
    
                if (e.security != null && !e.security.isBlank() && !"stock".equalsIgnoreCase(e.security)) {
                    msg.append("证券类型：").append(e.security).append(BR);
                }
            }
        }
    
        msg.append("说明：P = Purchase 买入，S = Sale 卖出；10b5-1 表示预设交易计划。");
    
        return msg.toString().trim();
    }
    
    private static String buildNoAlertNotification(
            String[] tickers,
            List<String> unmappedTickers,
            long minimumUsd,
            int lookbackDays,
            int processedCount,
            int failedCount,
            Set<String> tickersWithForm4) {

        StringBuilder msg = new StringBuilder();

        msg.append("📭 **内部人交易扫描完成，暂无大额交易提醒**\n\n");
        msg.append("🔎 扫描股票：").append(String.join(", ", tickers)).append("\n");
        msg.append("📆 扫描范围：最近 ").append(lookbackDays).append(" 天\n");
        msg.append("💰 提醒规则：买入不限金额，卖出 ≥ ").append(formatAmount(minimumUsd)).append("\n");
        msg.append("📄 已处理 Form 4：").append(processedCount).append(" 份\n");

        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append(" 份\n");
        }

        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append("\n");
        }

        msg.append("\n");

        if (tickersWithForm4 == null || tickersWithForm4.isEmpty()) {
            msg.append("结果：未发现相关 Form 4 披露。");
        } else {
            msg.append("结果：发现 Form 4 披露，但没有符合提醒规则的公开市场交易。\n\n");
            msg.append("有披露记录的股票：").append(String.join(", ", tickersWithForm4));
        }

        return msg.toString().trim();
    }

    private static String buildMissingNotification(
            String[] tickers,
            List<String> unmappedTickers,
            int lookbackDays,
            long minimumUsd) {

        StringBuilder msg = new StringBuilder();

        msg.append("📭 **未发现 Form 4 披露**\n\n");
        msg.append("🔎 扫描股票：").append(String.join(", ", tickers)).append("\n");
        msg.append("📆 扫描范围：最近 ").append(lookbackDays).append(" 天\n");
        msg.append("💰 提醒规则：买入不限金额，卖出 ≥ ").append(formatAmount(minimumUsd)).append("\n");

        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append("\n");
        }

        msg.append("\n");
        msg.append("结果：未找到匹配的 Form 4 内部人交易披露。\n\n");
        msg.append("可能原因：\n");
        msg.append("- 最近没有内部人交易披露\n");
        msg.append("- SEC 当日索引尚未更新\n");
        msg.append("- 股票代码未正确映射到 CIK\n");
        msg.append("- 披露存在延迟");

        return msg.toString().trim();
    }

    private static String buildNoValidCikNotification(String[] tickers, List<String> unmappedTickers) {
        StringBuilder msg = new StringBuilder();

        msg.append("⚠️ **未找到有效 CIK**\n\n");
        msg.append("输入股票：").append(String.join(", ", tickers)).append("\n");

        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("未映射股票：").append(String.join(", ", unmappedTickers)).append("\n");
        }

        msg.append("\n请检查股票代码是否正确，或补充 FALLBACK_TICKER_MAP。");
        return msg.toString().trim();
    }

    private static String buildConfigNotification(String title, String detail) {
        return "⚠️ **内部人交易机器人配置提醒**\n\n"
                + "问题：" + safeText(title, "配置异常") + "\n"
                + "说明：" + safeText(detail, "请检查启动参数和环境变量。");
    }

    private static String buildErrorNotification(String errorMessage) {
        StringBuilder msg = new StringBuilder();

        msg.append("🚨 **内部人交易机器人运行异常**\n\n");
        msg.append("错误信息：\n");
        msg.append("> ").append(safeText(errorMessage, "Unknown error")).append("\n\n");
        msg.append("建议检查：\n");
        msg.append("- SEC 网络访问是否正常\n");
        msg.append("- SEC_USER_AGENT / SEC_CONTACT_EMAIL 是否配置\n");
        msg.append("- DING_WEBHOOK_URL 或 DISCORD_WEBHOOK_URL 是否有效\n");
        msg.append("- 股票代码或 CIK 映射是否正确\n");
        msg.append("- XML 结构是否与当前解析逻辑兼容");

        return msg.toString().trim();
    }

    // ==================== 格式化 ====================

    private static String translatePosition(String eng) {
        if (eng == null || eng.isBlank()) {
            return "未知职位";
        }

        String cleaned = eng.trim();
        String lower = cleaned.toLowerCase(Locale.ROOT);

        if ("unknown position".equals(lower)) {
            return "未知职位";
        }

        String trans = POSITION_TRANSLATIONS.get(lower);
        if (trans != null) {
            return trans;
        }

        for (Map.Entry<String, String> entry : POSITION_TRANSLATIONS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return cleaned;
    }

    private static String formatDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return "N/A";
        }

        String clean = dateStr.trim().replace("-", "");
        if (clean.length() == 8 && clean.matches("\\d{8}")) {
            return clean.substring(0, 4) + "年" + clean.substring(4, 6) + "月" + clean.substring(6, 8) + "日";
        }

        return dateStr;
    }

    private static String formatNumber(long num) {
        if (num >= 1_000_000_000) {
            return String.format("%.2fB", num / 1_000_000_000.0);
        }
        if (num >= 1_000_000) {
            return String.format("%.2fM", num / 1_000_000.0);
        }
        if (num >= 10_000) {
            return String.format("%.1fK", num / 1_000.0);
        }
        return String.format("%,d", num);
    }

    private static String formatAmount(double amount) {
        double abs = Math.abs(amount);

        if (abs >= 1_000_000_000) {
            return String.format("$%.2fB", amount / 1_000_000_000.0);
        }
        if (abs >= 1_000_000) {
            return String.format("$%.2fM", amount / 1_000_000.0);
        }
        if (abs >= 1_000) {
            return String.format("$%.1fK", amount / 1_000.0);
        }
        return String.format("$%,.0f", amount);
    }

    private static String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    // ==================== 参数处理 ====================

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        String positional = null;

        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }

            if (arg.startsWith("--")) {
                String normalized = arg.substring(2);
                String[] parts = normalized.split("=", 2);
                if (parts.length == 2) {
                    options.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
                } else if (parts.length == 1) {
                    options.put(parts[0].toLowerCase(Locale.ROOT), "true");
                }
            } else if (positional == null) {
                positional = arg;
            }
        }

        options.put("positional", positional);
        return options;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    private static void logDebug(String message) {
        if (debugEnabled) {
            System.out.println("DEBUG: " + message);
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            if (value != null && !value.isBlank()) {
                return Long.parseLong(value.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            if (value != null && !value.isBlank()) {
                return Integer.parseInt(value.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return !(trimmed.equals("false") || trimmed.equals("0") || trimmed.equals("no") || trimmed.equals("off"));
    }

    private static String[] parseTickers(String tickersArg) {
        return Arrays.stream(tickersArg.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .distinct()
                .toArray(String[]::new);
    }

    // ==================== SEC 数据下载和索引解析 ====================

    private static Map<String, String> downloadTickerMapping() {
        Map<String, String> map = new HashMap<>();

        try {
            String content = downloadText(TICKER_URL);
            if (content != null && !content.isBlank()) {
                for (String line : content.split("\\R")) {
                    String[] parts = line.trim().split("\\t");
                    if (parts.length == 2) {
                        map.put(parts[0].toUpperCase(Locale.ROOT), parts[1]);
                    }
                }
            }
        } catch (Exception e) {
            logDebug("Failed to download SEC ticker mapping: " + e.getMessage());
        }

        if (map.isEmpty()) {
            map.putAll(FALLBACK_TICKER_MAP);
        }

        return map;
    }

    private static String findCikForTicker(String ticker, Map<String, String> tickerToCik) {
        String cleanInput = normalizeTickerForCompare(ticker);
        if (cleanInput.isBlank()) {
            return null;
        }

        for (Map.Entry<String, String> entry : tickerToCik.entrySet()) {
            if (normalizeTickerForCompare(entry.getKey()).equals(cleanInput)) {
                return entry.getValue();
            }
        }

        for (Map.Entry<String, String> entry : FALLBACK_TICKER_MAP.entrySet()) {
            if (normalizeTickerForCompare(entry.getKey()).equals(cleanInput)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private static String normalizeTickerForCompare(String ticker) {
        if (ticker == null) {
            return "";
        }
        return ticker.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static MasterIndex findMasterIndex(LocalDate startDate, int maxLookbackDays) {
        LocalDate date = startDate;
        StringBuilder combinedContent = new StringBuilder();
        LocalDate foundDate = null;

        for (int i = 0; i < maxLookbackDays; i++) {
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String url = SEC_BASE + "edgar/daily-index/" + date.getYear()
                    + "/QTR" + ((date.getMonthValue() - 1) / 3 + 1)
                    + "/master." + dateStr + ".idx";

            try {
                String content = downloadText(url);
                if (content != null && !content.isBlank()) {
                    logDebug("Using SEC index: " + url);
                    combinedContent.append(content);
                    if (foundDate == null) {
                        foundDate = date;
                    }
                }
            } catch (Exception e) {
                logDebug("Master index not available: " + url + " - " + e.getMessage());
            }

            date = date.minusDays(1);
        }

        return combinedContent.length() > 0 && foundDate != null
                ? new MasterIndex(foundDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")), combinedContent.toString())
                : null;
    }

    private static String downloadText(String url) throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpGet get = new HttpGet(url);

                String userAgent = firstNonBlank(System.getenv("SEC_USER_AGENT"), DEFAULT_SEC_USER_AGENT);
                String contactEmail = firstNonBlank(System.getenv("SEC_CONTACT_EMAIL"), DEFAULT_SEC_CONTACT_EMAIL);

                get.setHeader("User-Agent", userAgent);
                get.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                get.setHeader("Accept-Language", "en-US,en;q=0.9");
                get.setHeader("From", contactEmail);

                try (ClassicHttpResponse response = client.execute(get)) {
                    int status = response.getCode();

                    if (status == HttpStatus.SC_OK) {
                        HttpEntity entity = response.getEntity();
                        if (entity == null) {
                            throw new IllegalStateException("Empty response from " + url);
                        }
                        return EntityUtils.toString(entity);
                    }

                    if (status == 403 || status == 404) {
                        throw new IllegalStateException("HTTP " + status + " for " + url);
                    }

                    lastException = new IllegalStateException(
                            "HTTP " + status + " for " + url + " (attempt " + attempt + ")"
                    );

                    if (attempt < 3) {
                        Thread.sleep(2000);
                    }
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < 3) {
                    Thread.sleep(1000);
                }
            }
        }

        throw lastException != null
                ? lastException
                : new IllegalStateException("Failed to download " + url + " after 3 attempts");
    }

    private static List<String> parseMasterIdx(String content, Set<String> ciks) {
        Set<String> cleanCiks = new HashSet<>();
        for (String cik : ciks) {
            cleanCiks.add(cik.replaceFirst("^0+(?!$)", ""));
        }

        Set<String> urls = new LinkedHashSet<>();
        if (content == null) {
            return new ArrayList<>(urls);
        }

        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("CIK|") || line.startsWith("-----")) {
                continue;
            }

            String[] parts = line.split("\\|", 6);
            if (parts.length < 5) {
                continue;
            }

            String fileCik = parts[0].trim().replaceFirst("^0+(?!$)", "");
            String formType = parts[2].trim();

            if (!formType.startsWith("4")) {
                continue;
            }

            if (cleanCiks.contains(fileCik)) {
                String filename = parts[4].trim();
                if (!filename.isEmpty()) {
                    urls.add(SEC_BASE + filename);
                }
            }
        }

        return new ArrayList<>(urls);
    }

    private static List<String> fetchForm4UrlsFromEdgarBrowse(Set<String> ciks, int maxLookbackDays) {
        Set<String> urls = new LinkedHashSet<>();

        for (String cik : ciks) {
            try {
                String browseUrl = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=" + cik
                        + "&type=4&owner=include&count=100&output=atom";
                String atomXml = downloadText(browseUrl);
                if (atomXml != null && !atomXml.isBlank()) {
                    urls.addAll(parseBrowseEdgarAtom(atomXml, maxLookbackDays));
                }
            } catch (Exception e) {
                System.err.println("Warning: browse-edgar fallback failed for CIK " + cik + " - " + e.getMessage());
            }
        }

        return new ArrayList<>(urls);
    }

    private static List<String> parseBrowseEdgarAtom(String atomXml, int maxLookbackDays) {
        Set<String> urls = new LinkedHashSet<>();
        LocalDate threshold = LocalDate.now(ZoneId.of("America/New_York")).minusDays(maxLookbackDays);

        Pattern entryPattern = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern datePattern = Pattern.compile("<filing-date>(.*?)</filing-date>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern hrefPattern = Pattern.compile("<filing-href>(.*?)</filing-href>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher entryMatcher = entryPattern.matcher(atomXml);
        while (entryMatcher.find()) {
            String entry = entryMatcher.group(1);
            Matcher dateMatcher = datePattern.matcher(entry);
            Matcher hrefMatcher = hrefPattern.matcher(entry);

            if (!dateMatcher.find() || !hrefMatcher.find()) {
                continue;
            }

            String filingDate = dateMatcher.group(1).trim();
            String filingHref = hrefMatcher.group(1).trim();

            try {
                LocalDate date = LocalDate.parse(filingDate);
                if (date.isBefore(threshold)) {
                    continue;
                }

                String xmlUrl = findForm4XmlUrlFromIndexPage(filingHref);
                if (xmlUrl != null) {
                    urls.add(xmlUrl);
                }
            } catch (Exception e) {
                logDebug("Failed to parse browse-edgar entry: " + e.getMessage());
            }
        }

        return new ArrayList<>(urls);
    }

    private static String findForm4XmlUrlFromIndexPage(String indexUrl) {
        try {
            String html = downloadText(indexUrl);
            if (html == null || html.isBlank()) {
                return null;
            }

            Pattern xmlLinkPattern = Pattern.compile("href=\"([^\"]*?/form4\\.xml)\"", Pattern.CASE_INSENSITIVE);
            Matcher matcher = xmlLinkPattern.matcher(html);
            String bestUrl = null;

            while (matcher.find()) {
                String relative = matcher.group(1).trim();
                String fullUrl = relative.startsWith("http") ? relative : "https://www.sec.gov" + relative;

                if (!relative.toLowerCase(Locale.ROOT).contains("xslf345")) {
                    return fullUrl;
                }

                if (bestUrl == null) {
                    bestUrl = fullUrl;
                }
            }

            return bestUrl;
        } catch (Exception e) {
            logDebug("Failed to find Form 4 XML URL from index page: " + e.getMessage());
            return null;
        }
    }

    // ==================== Form 4 解析 ====================

    private static Map<String, List<AlertEntry>> parseForm4(
            String xml,
            long minimumUsd,
            Map<String, String> cikToRequestedTicker) throws Exception {

        Map<String, List<AlertEntry>> alerts = new LinkedHashMap<>();
        String xmlPayload = extractXmlPayload(xml);

        if (xmlPayload.isBlank()) {
            logDebug("Skipping file: Could not extract valid XML payload.");
            return alerts;
        }

        XmlMapper mapper = new XmlMapper();
        JsonNode root = mapper.readTree(xmlPayload);

        JsonNode issuer = root.path("issuer");
        String rawXmlCik = extractText(issuer, "issuerCik", extractText(issuer, "issuerCIK", "Unknown"));
        String normalizedXmlCik = rawXmlCik.replaceFirst("^0+(?!$)", "");
        String ticker = cikToRequestedTicker.getOrDefault(
                normalizedXmlCik,
                extractText(issuer, "issuerTradingSymbol", "Unknown")
        );

        JsonNode reportingOwnerNode = root.path("reportingOwner");
        JsonNode primaryOwner = reportingOwnerNode;

        if (reportingOwnerNode.isArray()) {
            boolean found = false;
            for (JsonNode node : reportingOwnerNode) {
                if (isValidReporter(node)) {
                    primaryOwner = node;
                    found = true;
                    break;
                }
            }
            if (!found && reportingOwnerNode.size() > 0) {
                primaryOwner = reportingOwnerNode.get(0);
            }
        }

        if (!isValidReporter(primaryOwner)) {
            logDebug("Skipping Form 4 for " + ticker + " - reporter is not a valid insider/officer/director.");
            alerts.putIfAbsent(ticker, new ArrayList<>());
            return alerts;
        }

        String ownerName = extractText(primaryOwner, "reportingOwnerId.rptOwnerName", "Unknown Owner");
        String position = extractPosition(primaryOwner);

        JsonNode nonDeriv = root.path("nonDerivativeTable");
        if (nonDeriv.isMissingNode()) {
            nonDeriv = root.path("ownershipDocument").path("nonDerivativeTable");
        }

        if (!nonDeriv.isMissingNode()) {
            JsonNode nonTrans = nonDeriv.path("nonDerivativeTransaction");
            if (!nonTrans.isMissingNode()) {
                if (nonTrans.isArray()) {
                    for (JsonNode tx : nonTrans) {
                        AlertEntry entry = processTransaction(tx, ownerName, position, minimumUsd);
                        if (entry != null) {
                            alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                        }
                    }
                } else if (nonTrans.isObject()) {
                    AlertEntry entry = processTransaction(nonTrans, ownerName, position, minimumUsd);
                    if (entry != null) {
                        alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                    }
                }
            } else {
                logDebug("No non-derivativeTransaction for " + ticker);
            }
        } else {
            logDebug("No non-derivativeTable for " + ticker);
        }

        alerts.putIfAbsent(ticker, new ArrayList<>());
        return alerts;
    }

    private static boolean isValidReporter(JsonNode reportingOwner) {
        String isDir = extractText(reportingOwner, "reportingOwnerRelationship.isDirector", "false");
        String isOff = extractText(reportingOwner, "reportingOwnerRelationship.isOfficer", "false");
        String isTen = extractText(reportingOwner, "reportingOwnerRelationship.isTenPercentOwner", "false");
        String isOth = extractText(reportingOwner, "reportingOwnerRelationship.isOther", "false");

        if ("true".equalsIgnoreCase(isDir) || "1".equals(isDir)
                || "true".equalsIgnoreCase(isOff) || "1".equals(isOff)
                || "true".equalsIgnoreCase(isTen) || "1".equals(isTen)
                || "true".equalsIgnoreCase(isOth) || "1".equals(isOth)) {
            return true;
        }

        return !extractText(reportingOwner, "reportingOwnerRelationship.officerTitle", "").isBlank();
    }

    private static String extractPosition(JsonNode reportingOwner) {
        List<String> titles = new ArrayList<>();

        String off = extractText(reportingOwner, "reportingOwnerRelationship.officerTitle", "");
        if (!off.isBlank()) {
            titles.add(off);
        }

        String dir = extractText(reportingOwner, "reportingOwnerRelationship.directorTitle", "");
        if (!dir.isBlank()) {
            titles.add(dir);
        }

        String oth = extractText(reportingOwner, "reportingOwnerRelationship.otherTitle", "");
        if (!oth.isBlank()) {
            titles.add(oth);
        }

        if (!titles.isEmpty()) {
            return String.join(", ", titles);
        }

        String rel = extractText(reportingOwner, "reportingOwnerRelationship.relationshipTitle", "");
        if (!rel.isBlank()) {
            return rel;
        }

        String rpt = extractText(reportingOwner, "reportingOwnerId.rptOwnerTitle", "");
        if (!rpt.isBlank()) {
            return rpt;
        }

        return "Unknown Position";
    }

    private static AlertEntry processTransaction(JsonNode transaction, String ownerName, String position, long minimumUsd) {
        String code = extractText(transaction, "transactionCoding.transactionCode", "");
    
        if (!"P".equalsIgnoreCase(code) && !"S".equalsIgnoreCase(code)) {
            logDebug("Skipping transaction: code=" + code + " (not P/S)");
            return null;
        }
    
        long shares = extractLong(transaction, "transactionAmounts.transactionShares");
        if (shares <= 0) {
            shares = extractLong(transaction, "transactionShares");
        }
    
        double price = extractDouble(transaction, "transactionAmounts.transactionPricePerShare");
        if (price <= 0) {
            price = extractDouble(transaction, "transactionPricePerShare");
        }
    
        if (shares <= 0 || price <= 0) {
            logDebug("Skipping transaction: code=" + code + " shares=" + shares + " price=" + price);
            return null;
        }
    
        double amount = shares * price;
        String type = "P".equalsIgnoreCase(code) ? "BUY" : "SELL";
    
        // 买入不限制金额；卖出才按 minimumUsd 阈值过滤
        if ("SELL".equals(type) && amount < minimumUsd) {
            logDebug("Skipping SELL transaction: amount=" + amount + " < threshold=" + minimumUsd);
            return null;
        }
    
        String security = extractText(transaction, "securityTitle", "stock");
        String is10b51 = extractText(transaction, "transactionCoding.is10b51Transaction", "false");
        boolean isPlan = "true".equalsIgnoreCase(is10b51) || "1".equals(is10b51);
    
        String transactionDate = extractText(transaction, "transactionDate", "");
        if (!transactionDate.isEmpty() && transactionDate.length() >= 10) {
            transactionDate = transactionDate.substring(0, 10);
        }
    
        long sharesOwnedAfter = extractLong(transaction, "postTransactionAmounts.sharesOwnedFollowingTransaction");
        if (sharesOwnedAfter <= 0) {
            sharesOwnedAfter = extractLong(transaction, "sharesOwnedFollowingTransaction");
        }
    
        logDebug("Creating alert: " + ownerName + " " + type + " " + shares + " shares at " + price
                + " amount=" + amount + " date=" + transactionDate + " ownedAfter=" + sharesOwnedAfter);
    
        return new AlertEntry(
                ownerName,
                position,
                type,
                security,
                shares,
                price,
                amount,
                isPlan,
                transactionDate,
                sharesOwnedAfter
        );
    }

    // ==================== XML / JSON 节点处理 ====================

    private static String extractText(JsonNode root, String path, String fallback) {
        JsonNode node = nodeAt(root, path);

        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }

        if (node.isTextual()) {
            String text = node.asText();
            return text.isBlank() ? fallback : text;
        }

        if (node.isObject()) {
            JsonNode valueNode = node.path("value");
            if (!valueNode.isMissingNode() && !valueNode.asText().isBlank()) {
                return valueNode.asText();
            }

            JsonNode emptyKeyNode = node.path("");
            if (!emptyKeyNode.isMissingNode() && !emptyKeyNode.asText().isBlank()) {
                return emptyKeyNode.asText();
            }
        }

        String raw = node.asText();
        if (raw != null && !raw.isBlank()) {
            return raw;
        }

        return fallback;
    }

    private static long extractLong(JsonNode root, String path) {
        JsonNode node = nodeAt(root, path);

        if (node.isMissingNode() || node.isNull()) {
            return 0;
        }

        if (node.isNumber()) {
            return node.asLong(0);
        }

        if (node.isTextual() && !node.asText().isBlank()) {
            return parseLongSafely(node.asText());
        }

        if (node.isObject()) {
            JsonNode valueNode = node.path("value");
            if (!valueNode.isMissingNode() && !valueNode.isNull()) {
                if (valueNode.isNumber()) {
                    return valueNode.asLong(0);
                }
                if (valueNode.isTextual() && !valueNode.asText().isBlank()) {
                    return parseLongSafely(valueNode.asText());
                }
            }

            JsonNode emptyKeyNode = node.path("");
            if (!emptyKeyNode.isMissingNode() && !emptyKeyNode.asText().isBlank()) {
                return parseLongSafely(emptyKeyNode.asText());
            }
        }

        return 0;
    }

    private static double extractDouble(JsonNode root, String path) {
        JsonNode node = nodeAt(root, path);

        if (node.isMissingNode() || node.isNull()) {
            return 0.0;
        }

        if (node.isNumber()) {
            return node.asDouble(0.0);
        }

        if (node.isTextual() && !node.asText().isBlank()) {
            return parseDoubleSafely(node.asText());
        }

        if (node.isObject()) {
            JsonNode valueNode = node.path("value");
            if (!valueNode.isMissingNode() && !valueNode.isNull()) {
                if (valueNode.isNumber()) {
                    return valueNode.asDouble(0.0);
                }
                if (valueNode.isTextual() && !valueNode.asText().isBlank()) {
                    return parseDoubleSafely(valueNode.asText());
                }
            }

            JsonNode emptyKeyNode = node.path("");
            if (!emptyKeyNode.isMissingNode() && !emptyKeyNode.asText().isBlank()) {
                return parseDoubleSafely(emptyKeyNode.asText());
            }
        }

        return 0.0;
    }

    private static JsonNode nodeAt(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            node = node.path(part);
            if (node.isMissingNode()) {
                return node;
            }
        }
        return node;
    }

    private static long parseLongSafely(String text) {
        try {
            return (long) Double.parseDouble(text.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDoubleSafely(String text) {
        try {
            return Double.parseDouble(text.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String extractXmlPayload(String rawText) {
        if (rawText == null) {
            return "";
        }

        String cleanXml = "";
        int xmlStart = rawText.indexOf("<XML>");
        if (xmlStart >= 0) {
            int xmlEnd = rawText.indexOf("</XML>", xmlStart);
            if (xmlEnd > xmlStart) {
                cleanXml = rawText.substring(xmlStart + 5, xmlEnd);
            }
        }

        if (cleanXml.isBlank()) {
            Matcher m = Pattern.compile(
                    "<ownershipDocument[^>]*>.*?</ownershipDocument>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            ).matcher(rawText);
            if (m.find()) {
                cleanXml = m.group(0);
            }
        }

        if (cleanXml.isBlank()) {
            return "";
        }

        cleanXml = cleanXml.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        cleanXml = cleanXml.replaceAll("&(?!(amp|apos|quot|lt|gt|#\\d+);)", "&amp;");
        cleanXml = cleanXml.replaceAll("</\\s+", "</");
        cleanXml = cleanXml.replaceAll("<\\s+(?=[a-zA-Z_/?!])", "<");
        cleanXml = cleanXml.replaceAll("<(?=[^a-zA-Z_/?!])", "&lt;");

        return cleanXml.trim();
    }

    // ==================== 通知发送 ====================

    private static boolean sendNotification(String message) {
        String dingTalkUrl = System.getenv("DING_WEBHOOK_URL");
        if (dingTalkUrl != null && !dingTalkUrl.isBlank()) {
            String dingTalkSecret = System.getenv("DING_WEBHOOK_SIGN");
            return sendDingTalkWebhook(dingTalkUrl, dingTalkSecret, "内部人交易警报", message);
        }

        String discordUrl = System.getenv("DISCORD_WEBHOOK_URL");
        if (discordUrl == null || discordUrl.isBlank()) {
            return false;
        }

        return sendDiscordWebhook(discordUrl, "Insider Alert", message);
    }

    private static void sendErrorNotification(String errorMessage) {
        String dingTalkUrl = System.getenv("DING_WEBHOOK_URL");
        if (dingTalkUrl != null && !dingTalkUrl.isBlank()) {
            String dingTalkSecret = System.getenv("DING_WEBHOOK_SIGN");
            sendDingTalkWebhook(dingTalkUrl, dingTalkSecret, "内部人交易机器人错误", errorMessage);
            return;
        }

        String discordUrl = System.getenv("DISCORD_WEBHOOK_URL");
        if (discordUrl != null && !discordUrl.isBlank()) {
            sendDiscordWebhook(discordUrl, "Insider Bot Error", errorMessage);
        }
    }

    private static boolean sendDingTalkWebhook(String webhookUrl, String secret, String title, String message) {
        try {
            String signedUrl = buildDingTalkUrl(webhookUrl, secret);
    
            // 钉钉标题只显示一次，并在标题前加 🔔
            String markdown = "### 🔔 " + title + "\n\n" + message;
    
            String payload = "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\""
                    + escapeJson(title) + "\",\"text\":\"" + escapeJson(markdown) + "\"}}";
    
            HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(signedUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
    
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
    
            boolean success = response.statusCode() >= 200
                    && response.statusCode() < 300
                    && body.replace(" ", "").contains("\"errcode\":0");
    
            if (!success) {
                System.err.println("Warning: DingTalk notification failed. status="
                        + response.statusCode() + " body=" + body);
            }
    
            return success;
        } catch (Exception e) {
            System.err.println("Warning: failed to send DingTalk notification: " + e.getMessage());
            return false;
        }
    }    

    private static String buildDingTalkUrl(String webhookUrl, String secret) throws Exception {
        if (secret == null || secret.isBlank()) {
            return webhookUrl;
        }

        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));

        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
        String separator = webhookUrl.contains("?") ? "&" : "?";

        return webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + sign;
    }

    private static boolean sendDiscordWebhook(String webhookUrl, String title, String message) {
        try {
            return sendDiscordMessages(webhookUrl, title, message);
        } catch (Exception e) {
            System.err.println("Warning: failed to send Discord notification: " + e.getMessage());
            return false;
        }
    }

    private static boolean sendDiscordMessages(String webhookUrl, String title, String message) throws Exception {
        String titleLine = "**" + title + "**\n";
        String fullBody = titleLine + message;

        if (escapeJson(fullBody).length() <= 2000) {
            return sendSingleDiscordMessage(webhookUrl, fullBody);
        }

        String[] lines = fullBody.split("\n", -1);
        StringBuilder chunk = new StringBuilder();
        boolean success = true;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String newline = (i < lines.length - 1) ? "\n" : "";
            String candidate = chunk + line + newline;

            if (escapeJson(candidate).length() <= 2000) {
                chunk.append(line).append(newline);
            } else {
                if (chunk.length() > 0) {
                    if (!sendSingleDiscordMessage(webhookUrl, chunk.toString())) {
                        success = false;
                    }
                    chunk.setLength(0);
                }

                String newCandidate = line + newline;
                if (escapeJson(newCandidate).length() > 2000) {
                    newCandidate = newCandidate.substring(0, Math.min(1800, newCandidate.length()))
                            + "\n...(内容过长已截断)";
                }
                chunk.append(newCandidate);
            }
        }

        if (chunk.length() > 0) {
            if (!sendSingleDiscordMessage(webhookUrl, chunk.toString())) {
                success = false;
            }
        }

        return success;
    }

    private static boolean sendSingleDiscordMessage(String webhookUrl, String content) {
        try {
            String payload = "{\"content\":\"" + escapeJson(content) + "\"}";

            HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            System.err.println("Warning: failed to send single Discord message: " + e.getMessage());
            return false;
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
