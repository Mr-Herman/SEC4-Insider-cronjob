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

public class Form144Bot {
    private static final String SEC_BASE = "https://www.sec.gov/Archives/";
    private static final String TICKER_URL = "https://www.sec.gov/include/ticker.txt";
    private static final String DEFAULT_SEC_USER_AGENT = "SEC-Form144-Bot AdminContact@example.com";
    private static final String DEFAULT_SEC_CONTACT_EMAIL = "contact@example.com";
    private static final int DEFAULT_MAX_LOOKBACK_DAYS = 7;
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

            logDebug("Form144 debug mode enabled: " + debug);
            logDebug("Tickers: " + tickersArg);
            logDebug("Lookback days: " + lookbackDays);

            if (tickersArg == null || tickersArg.isBlank()) {
                sendNotification("Form 144 计划卖出提醒", buildConfigNotification("未提供股票代码", "请配置 TICKERS 环境变量。"));
                return;
            }

            String[] tickers = parseTickers(tickersArg);
            Map<String, String> tickerToCik = downloadTickerMapping();
            Map<String, String> issuerCikToTicker = new HashMap<>();
            List<String> unmappedTickers = new ArrayList<>();

            for (String ticker : tickers) {
                String cik = findCikForTicker(ticker, tickerToCik);
                if (cik == null) {
                    unmappedTickers.add(ticker);
                    System.err.println("Warning: ticker not found in SEC mapping: " + ticker);
                } else {
                    String normalized = normalizeCik(cik);
                    issuerCikToTicker.put(normalized, ticker);
                    logDebug("Ticker mapped: " + ticker + " -> " + normalized);
                }
            }

            if (issuerCikToTicker.isEmpty()) {
                String msg = buildNoValidCikNotification(tickers, unmappedTickers);
                System.out.println(msg);
                sendNotification("Form 144 计划卖出提醒", msg);
                return;
            }

            MasterIndex masterIndex = findMasterIndex(LocalDate.now(ZoneId.of("America/New_York")), lookbackDays);
            if (masterIndex == null) {
                String msg = buildErrorNotification("最近 " + lookbackDays + " 天未找到可用 SEC master index。");
                System.out.println(msg);
                sendNotification("Form 144 计划卖出异常", msg);
                return;
            }

            List<IndexFiling> candidates = parseForm144Idx(masterIndex.content);
            logDebug("Master index lookup returned " + candidates.size() + " Form 144 candidate filings.");

            Map<String, List<Form144Entry>> alertsByTicker = new LinkedHashMap<>();
            int processedCount = 0;
            int failedCount = 0;
            int matchedCount = 0;

            for (IndexFiling filing : candidates) {
                try {
                    String text = downloadText(filing.url);
                    processedCount++;
                    Form144Entry entry = parseForm144Filing(text, filing, issuerCikToTicker);
                    if (entry != null) {
                        matchedCount++;
                        alertsByTicker.computeIfAbsent(entry.ticker, k -> new ArrayList<>()).add(entry);
                        logDebug("Creating Form144 alert: " + entry.ticker + " filer=" + entry.sellerName
                                + " shares=" + entry.sharesToSell + " value=" + entry.marketValue);
                    }
                } catch (Exception ex) {
                    failedCount++;
                    System.err.println("Warning: failed to process Form 144 at " + filing.url + " - " + ex.getMessage());
                }
            }

            String msg;
            if (alertsByTicker.isEmpty()) {
                msg = buildNoAlertNotification(tickers, unmappedTickers, lookbackDays, candidates.size(), processedCount, failedCount);
            } else {
                msg = buildForm144Notification(alertsByTicker, lookbackDays, candidates.size(), processedCount, matchedCount, failedCount, unmappedTickers);
            }

            boolean notified = sendNotification("Form 144 计划卖出提醒", msg);
            System.out.println("Found " + matchedCount + " Form 144 alert(s) in " + alertsByTicker.size()
                    + " ticker(s). Notification sent: " + notified);
            if (!notified) {
                System.out.println(msg);
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            sendNotification("Form 144 计划卖出异常", buildErrorNotification(e.getMessage()));
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
        final String filerCik;
        final String filerName;
        final String formType;
        final String filingDate;
        final String url;
        IndexFiling(String filerCik, String filerName, String formType, String filingDate, String url) {
            this.filerCik = filerCik;
            this.filerName = filerName;
            this.formType = formType;
            this.filingDate = filingDate;
            this.url = url;
        }
    }

    private static class Form144Entry {
        final String ticker;
        final String sellerName;
        final String relationship;
        final String filingDate;
        final String approximateSaleDate;
        final String sharesToSell;
        final String marketValue;
        final String securitiesTitle;
        final String riskLevel;
        final String analysis;
        final String url;

        Form144Entry(String ticker, String sellerName, String relationship, String filingDate,
                     String approximateSaleDate, String sharesToSell, String marketValue,
                     String securitiesTitle, String riskLevel, String analysis, String url) {
            this.ticker = ticker;
            this.sellerName = sellerName;
            this.relationship = relationship;
            this.filingDate = filingDate;
            this.approximateSaleDate = approximateSaleDate;
            this.sharesToSell = sharesToSell;
            this.marketValue = marketValue;
            this.securitiesTitle = securitiesTitle;
            this.riskLevel = riskLevel;
            this.analysis = analysis;
            this.url = url;
        }
    }

    private static Form144Entry parseForm144Filing(String rawText, IndexFiling filing, Map<String, String> issuerCikToTicker) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }

        String issuerCik = extractIssuerCik(rawText);
        String ticker = issuerCikToTicker.get(normalizeCik(issuerCik));
        if (ticker == null || ticker.isBlank()) {
            logDebug("Form 144 not matched. issuerCik=" + normalizeCik(issuerCik) + " url=" + filing.url);
            return null;
        }

        String filingDate = firstNonBlank(
                extractByRegex(rawText, "(?im)^\\s*FILED AS OF DATE:\\s*([0-9]{8})"),
                filing.filingDate
        );
        String sellerName = firstNonBlank(
                extractByRegex(rawText, "(?is)REPORTING-OWNER:.*?OWNER DATA:.*?COMPANY CONFORMED NAME:\\s*([^\\r\\n]+)"),
                extractByRegex(rawText, "(?is)FILED BY:.*?COMPANY CONFORMED NAME:\\s*([^\\r\\n]+)"),
                filing.filerName,
                "Unknown Seller"
        );
        String compact = compactText(rawText);
        String relationship = firstNonBlank(
                extractByRegex(compact, "(?is)(Director|Officer|10% Owner|Affiliate|Executive|CEO|CFO|President)"),
                "关联方/内部人"
        );
        String securitiesTitle = firstNonBlank(
                extractByRegex(compact, "(?is)(Common Stock|Ordinary Shares|Class A Common Stock|Class B Common Stock|Preferred Stock)"),
                "Common Stock"
        );
        String sharesToSell = firstNonBlank(
                extractLabeledNumber(compact, "(?:shares|units).{0,80}(?:to be sold|proposed to be sold|sold)"),
                extractByRegex(compact, "(?is)([0-9][0-9,]{3,})\\s+(?:shares|units)")
        );
        String marketValue = firstNonBlank(
                extractMoneyNearLabel(compact, "aggregate market value"),
                extractMoneyNearLabel(compact, "approximate date of sale"),
                extractByRegex(compact, "(?is)\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)")
        );
        String approximateSaleDate = firstNonBlank(
                extractByRegex(compact, "(?is)approximate date of sale.{0,120}?([0-9]{1,2}/[0-9]{1,2}/[0-9]{2,4})"),
                extractByRegex(compact, "(?is)approximate date of sale.{0,120}?([A-Za-z]+\\s+[0-9]{1,2},\\s*[0-9]{4})"),
                "N/A"
        );

        String riskLevel = estimateRiskLevel(sharesToSell, marketValue);
        String analysis = buildAnalysis(riskLevel, sellerName, marketValue);

        return new Form144Entry(
                ticker,
                cleanText(sellerName),
                cleanText(relationship),
                filingDate == null ? "" : filingDate.trim(),
                cleanText(approximateSaleDate),
                cleanText(sharesToSell),
                formatMoney(marketValue),
                cleanText(securitiesTitle),
                riskLevel,
                analysis,
                filing.url
        );
    }

    private static String extractIssuerCik(String rawText) {
        String issuer = extractByRegex(rawText, "(?is)ISSUER:.*?CENTRAL INDEX KEY:\\s*([0-9]+)");
        if (!issuer.isBlank()) {
            return issuer;
        }
        String subject = extractByRegex(rawText, "(?is)SUBJECT COMPANY:.*?CENTRAL INDEX KEY:\\s*([0-9]+)");
        if (!subject.isBlank()) {
            return subject;
        }
        return extractByRegex(rawText, "(?is)Name of Issuer[^0-9]{0,200}CIK[^0-9]{0,30}([0-9]+)");
    }

    private static String estimateRiskLevel(String sharesText, String valueText) {
        double value = parseMoney(valueText);
        long shares = parseLongSafely(sharesText);
        if (value >= 5_000_000 || shares >= 100_000) {
            return "🔴 高关注";
        }
        if (value >= 1_000_000 || shares >= 20_000) {
            return "🟡 中关注";
        }
        return "⚪ 低关注";
    }

    private static String buildAnalysis(String riskLevel, String sellerName, String marketValue) {
        if (riskLevel.startsWith("🔴")) {
            return "计划卖出规模较大，建议后续重点观察是否出现对应 Form 4 实际卖出，以及是否多名高管集中提交 Form 144。";
        }
        if (riskLevel.startsWith("🟡")) {
            return "计划卖出具备一定参考价值，但 Form 144 只代表卖出意图，不代表已经成交，需结合后续 Form 4 确认。";
        }
        return "计划卖出规模较小，单独参考价值有限，主要用于跟踪后续是否转化为实际卖出。";
    }

    private static String buildForm144Notification(Map<String, List<Form144Entry>> alertsByTicker,
                                                   int lookbackDays, int candidateCount, int processedCount,
                                                   int matchedCount, int failedCount, List<String> unmappedTickers) {
        final String br = "  \n";
        StringBuilder msg = new StringBuilder();
        msg.append("📋 **Form 144 关联方计划卖出提醒**\n\n");
        msg.append("🔎 扫描范围：最近 ").append(lookbackDays).append(" 天").append(br);
        msg.append("📄 候选 Form 144：").append(candidateCount).append(" 份").append(br);
        msg.append("📄 已处理：").append(processedCount).append(" 份").append(br);
        msg.append("📊 命中：").append(alertsByTicker.size()).append(" 个股票，").append(matchedCount).append(" 份计划卖出").append(br);
        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append(" 份").append(br);
        }
        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append(br);
        }
        msg.append('\n');

        for (Map.Entry<String, List<Form144Entry>> tickerEntry : alertsByTicker.entrySet()) {
            msg.append("## ").append(tickerEntry.getKey()).append('\n');
            for (Form144Entry entry : tickerEntry.getValue()) {
                msg.append(entry.riskLevel).append(" **计划卖出**").append(br);
                msg.append("申报人：").append(safeText(entry.sellerName, "Unknown Seller")).append(br);
                msg.append("关系/职位：").append(safeText(entry.relationship, "关联方/内部人")).append(br);
                msg.append("披露日期：").append(formatDate(entry.filingDate)).append(br);
                msg.append("预计卖出日期：").append(safeText(entry.approximateSaleDate, "N/A")).append(br);
                if (!entry.sharesToSell.isBlank()) {
                    msg.append("计划卖出数量：").append(entry.sharesToSell).append(br);
                }
                if (!entry.marketValue.isBlank()) {
                    msg.append("预计卖出金额：").append(entry.marketValue).append(br);
                }
                msg.append("证券类型：").append(safeText(entry.securitiesTitle, "N/A")).append(br);
                msg.append("分析：").append(entry.analysis).append(br);
                msg.append("SEC链接：").append(entry.url).append("\n\n");
            }
        }
        msg.append("说明：Form 144 表示关联方计划出售证券，不代表已经成交；后续应结合 Form 4 验证是否实际卖出。");
        return msg.toString().trim();
    }

    private static String buildNoAlertNotification(String[] tickers, List<String> unmappedTickers,
                                                   int lookbackDays, int candidateCount, int processedCount, int failedCount) {
        StringBuilder msg = new StringBuilder();
        msg.append("📭 **Form 144 扫描完成，暂无计划卖出提醒**\n\n");
        msg.append("🔎 扫描股票：").append(String.join(", ", tickers)).append('\n');
        msg.append("📆 扫描范围：最近 ").append(lookbackDays).append(" 天\n");
        msg.append("📄 候选 Form 144：").append(candidateCount).append(" 份\n");
        msg.append("📄 已处理：").append(processedCount).append(" 份\n");
        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append(" 份\n");
        }
        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append('\n');
        }
        msg.append("\n结果：未发现匹配这些股票的 Form 144 计划卖出披露。");
        return msg.toString().trim();
    }

    private static List<IndexFiling> parseForm144Idx(String content) {
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
            String formType = parts[2].trim().toUpperCase(Locale.ROOT);
            if (!formType.equals("144") && !formType.equals("144/A")) {
                continue;
            }
            String url = SEC_BASE + parts[4].trim();
            if (seenUrls.add(url)) {
                filings.add(new IndexFiling(normalizeCik(parts[0]), parts[1].trim(), parts[2].trim(), parts[3].trim(), url));
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

    private static String extractLabeledNumber(String compact, String labelRegex) {
        Matcher matcher = Pattern.compile("(?is)" + labelRegex + ".{0,120}?([0-9][0-9,]*)").matcher(compact);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static String extractMoneyNearLabel(String compact, String label) {
        Matcher matcher = Pattern.compile("(?is)" + Pattern.quote(label) + ".{0,200}?\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)").matcher(compact);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static String formatMoney(String value) {
        double money = parseMoney(value);
        if (money <= 0) {
            return cleanText(value);
        }
        if (money >= 1_000_000_000) {
            return String.format("$%.2fB", money / 1_000_000_000.0);
        }
        if (money >= 1_000_000) {
            return String.format("$%.2fM", money / 1_000_000.0);
        }
        if (money >= 1_000) {
            return String.format("$%.1fK", money / 1_000.0);
        }
        return String.format("$%,.0f", money);
    }

    private static double parseMoney(String value) {
        try {
            if (value == null || value.isBlank()) {
                return 0;
            }
            return Double.parseDouble(value.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLongSafely(String value) {
        try {
            if (value == null || value.isBlank()) {
                return 0;
            }
            return (long) Double.parseDouble(value.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return 0;
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

    private static String cleanText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String compactText(String value) {
        return value == null ? "" : value.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String extractByRegex(String text, String regex) {
        if (text == null) {
            return "";
        }
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String buildConfigNotification(String title, String detail) {
        return "⚠️ **Form 144 机器人配置提醒**\n\n问题：" + safeText(title, "配置异常")
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
        return "🚨 **Form 144 机器人运行异常**\n\n错误信息：\n> "
                + safeText(errorMessage, "Unknown error")
                + "\n\n建议检查：\n- SEC 网络访问是否正常\n- SEC_USER_AGENT / SEC_CONTACT_EMAIL 是否配置\n- DING_WEBHOOK_URL 或 DISCORD_WEBHOOK_URL 是否有效\n- Form 144 正文格式是否与当前解析逻辑兼容";
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
