package com.example;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class Event8KBot {
    private static final String SEC_BASE = "https://www.sec.gov/Archives/";
    private static final String TICKER_URL = "https://www.sec.gov/include/ticker.txt";
    private static final String DEFAULT_SEC_USER_AGENT = "SEC-8K-Event-Bot AdminContact@example.com";
    private static final String DEFAULT_SEC_CONTACT_EMAIL = "contact@example.com";
    private static final int DEFAULT_MAX_LOOKBACK_DAYS = 3;
    private static final boolean DEFAULT_DEBUG = true;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static final Map<String, String> FALLBACK_TICKER_MAP = Map.ofEntries(
            Map.entry("BRKB", "1067983"),
            Map.entry("BRK-B", "1067983"),
            Map.entry("MSFT", "0000789019"),
            Map.entry("ZTS", "0001555285"),
            Map.entry("STZ", "0001593873")
    );

    private static boolean debugEnabled = DEFAULT_DEBUG;

    public static void main(String[] args) {
        try {
            Map<String, String> options = parseOptions(args);
            String tickersArg = firstNonBlank(options.get("tickers"), System.getenv("TICKERS"), options.get("positional"));
            int lookbackDays = parseInt(firstNonBlank(options.get("lookback"), System.getenv("LOOKBACK_DAYS")), DEFAULT_MAX_LOOKBACK_DAYS);
            boolean debug = parseBoolean(firstNonBlank(options.get("debug"), System.getenv("DEBUG")), DEFAULT_DEBUG);
            setDebug(debug);

            logDebug("8-K debug mode enabled: " + debug);
            logDebug("Tickers: " + tickersArg);
            logDebug("Lookback days: " + lookbackDays);

            if (tickersArg == null || tickersArg.isBlank()) {
                sendNotification("8-K 重大事件提醒", buildConfigNotification("未提供股票代码", "请配置 TICKERS 环境变量。"));
                return;
            }

            String[] tickers = parseTickers(tickersArg);
            Map<String, String> tickerToCik = downloadTickerMapping();
            Map<String, String> cikToTicker = new HashMap<>();
            List<String> unmappedTickers = new ArrayList<>();

            for (String ticker : tickers) {
                String cik = findCikForTicker(ticker, tickerToCik);
                if (cik == null) {
                    unmappedTickers.add(ticker);
                    System.err.println("Warning: ticker not found in SEC mapping: " + ticker);
                } else {
                    String normalized = normalizeCik(cik);
                    cikToTicker.put(normalized, ticker);
                    logDebug("Ticker mapped: " + ticker + " -> " + normalized);
                }
            }

            if (cikToTicker.isEmpty()) {
                String msg = buildNoValidCikNotification(tickers, unmappedTickers);
                System.out.println(msg);
                sendNotification("8-K 重大事件提醒", msg);
                return;
            }

            MasterIndex masterIndex = findMasterIndex(LocalDate.now(ZoneId.of("America/New_York")), lookbackDays);
            if (masterIndex == null) {
                String msg = buildErrorNotification("最近 " + lookbackDays + " 天未找到可用 SEC master index。");
                System.out.println(msg);
                sendNotification("8-K 重大事件异常", msg);
                return;
            }

            List<IndexFiling> candidates = parse8KIdx(masterIndex.content, cikToTicker);
            logDebug("Master index lookup returned " + candidates.size() + " matched 8-K candidate filings.");

            Map<String, List<Event8KEntry>> alertsByTicker = new LinkedHashMap<>();
            int processedCount = 0;
            int failedCount = 0;
            int alertCount = 0;

            for (IndexFiling filing : candidates) {
                try {
                    String text = downloadText(filing.url);
                    processedCount++;
                    Event8KEntry entry = parse8KFiling(text, filing);
                    if (entry != null && entry.isHighValue()) {
                        alertCount++;
                        alertsByTicker.computeIfAbsent(entry.ticker, k -> new ArrayList<>()).add(entry);
                        logDebug("Creating 8-K alert: " + entry.ticker + " items=" + entry.itemsSummary + " risk=" + entry.riskLevel);
                    } else {
                        logDebug("Skipping low-value 8-K: " + filing.ticker + " url=" + filing.url);
                    }
                } catch (Exception ex) {
                    failedCount++;
                    System.err.println("Warning: failed to process 8-K at " + filing.url + " - " + ex.getMessage());
                }
            }

            String msg;
            if (alertsByTicker.isEmpty()) {
                msg = buildNoAlertNotification(tickers, unmappedTickers, lookbackDays, candidates.size(), processedCount, failedCount);
            } else {
                msg = build8KNotification(alertsByTicker, lookbackDays, candidates.size(), processedCount, alertCount, failedCount, unmappedTickers);
            }

            boolean notified = sendNotification("8-K 重大事件提醒", msg);
            System.out.println("Found " + alertCount + " high-value 8-K alert(s) in " + alertsByTicker.size()
                    + " ticker(s). Notification sent: " + notified);
            if (!notified) {
                System.out.println(msg);
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            sendNotification("8-K 重大事件异常", buildErrorNotification(e.getMessage()));
            System.exit(1);
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

    private static class IndexFiling {
        final String cik;
        final String ticker;
        final String companyName;
        final String formType;
        final String filingDate;
        final String url;
        IndexFiling(String cik, String ticker, String companyName, String formType, String filingDate, String url) {
            this.cik = cik;
            this.ticker = ticker;
            this.companyName = companyName;
            this.formType = formType;
            this.filingDate = filingDate;
            this.url = url;
        }
    }

    private static class Event8KEntry {
        final String ticker;
        final String companyName;
        final String filingDate;
        final List<String> items;
        final String itemsSummary;
        final String riskLevel;
        final String eventType;
        final String summary;
        final String url;

        Event8KEntry(String ticker, String companyName, String filingDate, List<String> items,
                     String riskLevel, String eventType, String summary, String url) {
            this.ticker = ticker;
            this.companyName = companyName;
            this.filingDate = filingDate;
            this.items = items;
            this.itemsSummary = String.join(", ", items);
            this.riskLevel = riskLevel;
            this.eventType = eventType;
            this.summary = summary;
            this.url = url;
        }

        boolean isHighValue() {
            return !items.isEmpty() && !riskLevel.startsWith("⚪");
        }
    }

    private static Event8KEntry parse8KFiling(String rawText, IndexFiling filing) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String compact = compactText(rawText);
        List<String> items = extract8KItems(compact);
        if (items.isEmpty()) {
            return null;
        }
        String risk = classifyRisk(items, compact);
        String eventType = classifyEventType(items, compact);
        String summary = buildSummary(items, compact);
        return new Event8KEntry(filing.ticker, filing.companyName, filing.filingDate, items, risk, eventType, summary, filing.url);
    }

    private static List<String> extract8KItems(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?i)Item\\s+([0-9]{1,2}\\.[0-9]{2})").matcher(text);
        Set<String> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            String code = matcher.group(1);
            if (isHighValueItem(code) && seen.add(code)) {
                result.add("Item " + code + " - " + itemName(code));
            }
        }
        return result;
    }

    private static boolean isHighValueItem(String code) {
        return code.equals("1.01")
                || code.equals("1.03")
                || code.equals("2.01")
                || code.equals("2.02")
                || code.equals("2.05")
                || code.equals("2.06")
                || code.equals("3.01")
                || code.equals("3.02")
                || code.equals("5.02")
                || code.equals("7.01")
                || code.equals("8.01")
                || code.equals("9.01");
    }

    private static String itemName(String code) {
        switch (code) {
            case "1.01": return "重大协议";
            case "1.03": return "破产/重组";
            case "2.01": return "并购或资产出售完成";
            case "2.02": return "业绩发布/业绩预告";
            case "2.05": return "退出或处置计划";
            case "2.06": return "资产减值";
            case "3.01": return "退市风险";
            case "3.02": return "未注册证券发行/融资";
            case "5.02": return "高管/董事变动";
            case "7.01": return "Reg FD 披露";
            case "8.01": return "其他重大事件";
            case "9.01": return "财务报表/附件";
            default: return "重大事件";
        }
    }

    private static String classifyRisk(List<String> items, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsItem(items, "1.03") || containsItem(items, "3.01") || containsItem(items, "2.06")
                || lower.contains("bankruptcy") || lower.contains("delisting") || lower.contains("impairment")) {
            return "🔴 高风险";
        }
        if (containsItem(items, "3.02") || lower.contains("private placement") || lower.contains("registered direct")
                || lower.contains("convertible") || lower.contains("atm offering")) {
            return "🔴 融资/稀释风险";
        }
        if (containsItem(items, "2.02") && (lower.contains("raises guidance") || lower.contains("increase guidance") || lower.contains("record revenue"))) {
            return "🟢 偏利好";
        }
        if (containsItem(items, "1.01") || containsItem(items, "2.01") || containsItem(items, "2.02") || containsItem(items, "5.02") || containsItem(items, "8.01")) {
            return "🟡 中关注";
        }
        return "⚪ 低关注";
    }

    private static String classifyEventType(List<String> items, String text) {
        if (containsItem(items, "1.03")) return "破产/重组";
        if (containsItem(items, "3.01")) return "退市风险";
        if (containsItem(items, "3.02")) return "融资/增发/私募";
        if (containsItem(items, "2.02")) return "业绩相关披露";
        if (containsItem(items, "1.01")) return "重大协议/合同";
        if (containsItem(items, "2.01")) return "并购/资产交易";
        if (containsItem(items, "5.02")) return "高管/董事变动";
        if (containsItem(items, "8.01")) return "其他重大事项";
        return "8-K 重大事件";
    }

    private static boolean containsItem(List<String> items, String code) {
        for (String item : items) {
            if (item.contains(code)) {
                return true;
            }
        }
        return false;
    }

    private static String buildSummary(List<String> items, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> points = new ArrayList<>();
        if (containsItem(items, "1.01")) points.add("公司披露重大协议或重要合同，建议关注合同金额、客户质量和收入确认节奏。");
        if (containsItem(items, "2.02")) points.add("公司披露业绩或业绩相关材料，建议结合 Exhibit 99.1 查看收入、利润率、指引变化。");
        if (containsItem(items, "3.02")) points.add("公司披露未注册证券发行，可能涉及融资、增发、可转债或股权稀释。需重点关注发行规模和价格。");
        if (containsItem(items, "3.01")) points.add("公司披露交易所通知或退市相关风险，属于高风险事件。需关注整改期限。");
        if (containsItem(items, "5.02")) points.add("公司披露高管或董事变动，需关注是否为 CEO/CFO 离职、突然辞任或管理层重组。");
        if (containsItem(items, "2.06")) points.add("公司披露资产减值，可能影响利润和资产质量。需关注减值金额。");
        if (containsItem(items, "1.03")) points.add("公司披露破产或重组相关事项，属于极高风险事件。");
        if (lower.contains("exhibit 99.1")) points.add("文件包含 Exhibit 99.1，通常是新闻稿、财报或投资者材料，建议打开 SEC 原文查看附件。");
        if (points.isEmpty()) {
            points.add("发现高价值 8-K Item，建议打开 SEC 原文查看具体内容。当前摘要基于 Item 类型自动判断。 ");
        }
        return String.join(" ", points);
    }

    private static String build8KNotification(Map<String, List<Event8KEntry>> alertsByTicker,
                                              int lookbackDays, int candidateCount, int processedCount,
                                              int alertCount, int failedCount, List<String> unmappedTickers) {
        final String br = "  \n";
        StringBuilder msg = new StringBuilder();
        msg.append("🚨 **8-K 重大事件提醒**\n\n");
        msg.append("🔎 扫描范围：最近 ").append(lookbackDays).append(" 天").append(br);
        msg.append("📄 匹配 8-K：").append(candidateCount).append(" 份").append(br);
        msg.append("📄 已处理：").append(processedCount).append(" 份").append(br);
        msg.append("📊 高价值事件：").append(alertsByTicker.size()).append(" 个股票，").append(alertCount).append(" 份披露").append(br);
        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append(" 份").append(br);
        }
        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append(br);
        }
        msg.append('\n');

        for (Map.Entry<String, List<Event8KEntry>> tickerEntry : alertsByTicker.entrySet()) {
            msg.append("## ").append(tickerEntry.getKey()).append('\n');
            for (Event8KEntry entry : tickerEntry.getValue()) {
                msg.append(entry.riskLevel).append(" **").append(entry.eventType).append("**").append(br);
                msg.append("公司：").append(safeText(entry.companyName, entry.ticker)).append(br);
                msg.append("披露日期：").append(formatDate(entry.filingDate)).append(br);
                msg.append("事件类型：").append(entry.itemsSummary).append(br);
                msg.append("分析：").append(entry.summary).append(br);
                msg.append("SEC链接：").append(entry.url).append("\n\n");
            }
        }
        msg.append("说明：8-K 为重大事件当前报告。本机器人按 Item 类型自动筛选高价值事件，具体影响需结合 SEC 原文和附件确认。");
        return msg.toString().trim();
    }

    private static String buildNoAlertNotification(String[] tickers, List<String> unmappedTickers,
                                                   int lookbackDays, int candidateCount, int processedCount, int failedCount) {
        StringBuilder msg = new StringBuilder();
        msg.append("📭 **8-K 扫描完成，暂无高价值重大事件**\n\n");
        msg.append("🔎 扫描股票：").append(String.join(", ", tickers)).append('\n');
        msg.append("📆 扫描范围：最近 ").append(lookbackDays).append(" 天\n");
        msg.append("📄 匹配 8-K：").append(candidateCount).append(" 份\n");
        msg.append("📄 已处理：").append(processedCount).append(" 份\n");
        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append(" 份\n");
        }
        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append('\n');
        }
        msg.append("\n结果：未发现匹配这些股票的高价值 8-K 重大事件。");
        return msg.toString().trim();
    }

    private static List<IndexFiling> parse8KIdx(String content, Map<String, String> cikToTicker) {
        List<IndexFiling> filings = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return filings;
        }
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("CIK|") || line.startsWith("-----")) {
                continue;
            }
            String[] parts = line.split("\\|", 6);
            if (parts.length < 5) {
                continue;
            }
            String cik = normalizeCik(parts[0]);
            String ticker = cikToTicker.get(cik);
            if (ticker == null) {
                continue;
            }
            String formType = parts[2].trim().toUpperCase(Locale.ROOT);
            if (!formType.equals("8-K") && !formType.equals("8-K/A")) {
                continue;
            }
            String url = SEC_BASE + parts[4].trim();
            if (seenUrls.add(url)) {
                filings.add(new IndexFiling(cik, ticker, parts[1].trim(), parts[2].trim(), parts[3].trim(), url));
            }
        }
        return filings;
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
                    combinedContent.append(content).append('\n');
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

    private static String downloadText(String url) throws Exception {
        Exception lastException = null;
        HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String userAgent = firstNonBlank(System.getenv("SEC_USER_AGENT"), DEFAULT_SEC_USER_AGENT);
                String contactEmail = firstNonBlank(System.getenv("SEC_CONTACT_EMAIL"), DEFAULT_SEC_CONTACT_EMAIL);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(HTTP_TIMEOUT)
                        .header("User-Agent", userAgent)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("From", contactEmail)
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                if (response.statusCode() == 403 || response.statusCode() == 404) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
                }
                lastException = new IllegalStateException("HTTP " + response.statusCode() + " for " + url + " attempt " + attempt);
                if (attempt < 3) {
                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < 3) {
                    Thread.sleep(1000);
                }
            }
        }
        throw lastException != null ? lastException : new IllegalStateException("Failed to download " + url);
    }

    private static boolean sendNotification(String title, String message) {
        String dingTalkUrl = System.getenv("DING_WEBHOOK_URL");
        if (dingTalkUrl != null && !dingTalkUrl.isBlank()) {
            return sendDingTalkWebhook(dingTalkUrl, System.getenv("DING_WEBHOOK_SIGN"), title, message);
        }
        String discordUrl = System.getenv("DISCORD_WEBHOOK_URL");
        if (discordUrl == null || discordUrl.isBlank()) {
            return false;
        }
        return sendDiscordWebhook(discordUrl, title, message);
    }

    private static boolean sendDingTalkWebhook(String webhookUrl, String secret, String title, String message) {
        try {
            String signedUrl = buildDingTalkUrl(webhookUrl, secret);
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
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300
                    && body.replace(" ", "").contains("\"errcode\":0");
            if (!success) {
                System.err.println("Warning: DingTalk notification failed. status=" + response.statusCode() + " body=" + body);
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
            String content = "**" + title + "**\n" + message;
            if (content.length() > 1900) {
                content = content.substring(0, 1900) + "\n...(内容过长已截断)";
            }
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
            System.err.println("Warning: failed to send Discord notification: " + e.getMessage());
            return false;
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        String positional = null;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (arg.startsWith("--")) {
                String[] parts = arg.substring(2).split("=", 2);
                options.put(parts[0].toLowerCase(Locale.ROOT), parts.length == 2 ? parts[1] : "true");
            } else if (positional == null) {
                positional = arg;
            }
        }
        options.put("positional", positional);
        return options;
    }

    private static String[] parseTickers(String tickersArg) {
        return Arrays.stream(tickersArg.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .distinct()
                .toArray(String[]::new);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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

    private static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    private static void logDebug(String message) {
        if (debugEnabled) {
            System.out.println("DEBUG: " + message);
        }
    }

    private static String normalizeTickerForCompare(String ticker) {
        return ticker == null ? "" : ticker.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String normalizeCik(String cik) {
        return cik == null ? "" : cik.trim().replaceFirst("^0+(?!$)", "");
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

    private static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String compactText(String value) {
        return value == null ? "" : value.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String buildConfigNotification(String title, String detail) {
        return "⚠️ **8-K 机器人配置提醒**\n\n问题：" + safeText(title, "配置异常")
                + "\n说明：" + safeText(detail, "请检查启动参数和环境变量。");
    }

    private static String buildNoValidCikNotification(String[] tickers, List<String> unmappedTickers) {
        StringBuilder msg = new StringBuilder();
        msg.append("⚠️ **未找到有效 CIK**\n\n");
        msg.append("输入股票：").append(String.join(", ", tickers)).append('\n');
        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("未映射股票：").append(String.join(", ", unmappedTickers)).append('\n');
        }
        msg.append("\n请检查股票代码是否正确，或补充 FALLBACK_TICKER_MAP。");
        return msg.toString().trim();
    }

    private static String buildErrorNotification(String errorMessage) {
        return "🚨 **8-K 机器人运行异常**\n\n错误信息：\n> "
                + safeText(errorMessage, "Unknown error")
                + "\n\n建议检查：\n- SEC 网络访问是否正常\n- SEC_USER_AGENT / SEC_CONTACT_EMAIL 是否配置\n- DING_WEBHOOK_URL 或 DISCORD_WEBHOOK_URL 是否有效\n- 8-K 正文格式是否与当前解析逻辑兼容";
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
