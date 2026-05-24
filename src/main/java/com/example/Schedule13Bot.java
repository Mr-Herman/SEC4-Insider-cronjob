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
import java.util.HashSet;
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

public class Schedule13Bot {

    private static final String SEC_BASE = "https://www.sec.gov/Archives/";
    private static final String TICKER_URL = "https://www.sec.gov/include/ticker.txt";
    private static final String DEFAULT_SEC_USER_AGENT = "SEC13D-13G-Bot AdminContact@example.com";
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

            String tickersArg = firstNonBlank(
                    options.get("tickers"),
                    System.getenv("TICKERS"),
                    options.get("positional")
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
            logDebug("Schedule13 debug mode enabled: " + debug);
            logDebug("Tickers: " + tickersArg);
            logDebug("Lookback days: " + maxLookbackDays);

            if (tickersArg == null || tickersArg.isBlank()) {
                String msg = buildConfigNotification(
                        "未提供股票代码",
                        "请配置 TICKERS 环境变量，或手动运行时输入 tickers。"
                );
                System.out.println(msg);
                sendNotification("13D/13G 大股东披露提醒", msg);
                return;
            }

            String[] tickers = parseTickers(tickersArg);
            if (tickers.length == 0) {
                String msg = buildConfigNotification(
                        "股票代码无效",
                        "输入内容中没有解析到有效股票代码。"
                );
                System.out.println(msg);
                sendNotification("13D/13G 大股东披露提醒", msg);
                return;
            }

            Map<String, String> tickerToCik = downloadTickerMapping();
            if (tickerToCik.isEmpty()) {
                String msg = buildErrorNotification("下载 SEC ticker mapping 失败，且 fallback 映射表为空。");
                System.err.println(msg);
                sendNotification("13D/13G 大股东披露异常", msg);
                return;
            }

            Map<String, String> subjectCikToTicker = new HashMap<>();
            List<String> unmappedTickers = new ArrayList<>();

            for (String ticker : tickers) {
                String cik = findCikForTicker(ticker, tickerToCik);
                if (cik != null) {
                    String normalizedCik = normalizeCik(cik);
                    subjectCikToTicker.put(normalizedCik, ticker);
                    logDebug("Ticker mapped: " + ticker + " -> " + normalizedCik);
                } else {
                    unmappedTickers.add(ticker);
                    System.err.println("Warning: ticker not found in SEC mapping: " + ticker);
                }
            }

            if (subjectCikToTicker.isEmpty()) {
                String msg = buildNoValidCikNotification(tickers, unmappedTickers);
                System.err.println(msg);
                sendNotification("13D/13G 大股东披露提醒", msg);
                return;
            }

            LocalDate currentDate = LocalDate.now(ZoneId.of("America/New_York"));
            MasterIndex masterIndex = findMasterIndex(currentDate, maxLookbackDays);
            if (masterIndex == null) {
                String msg = buildErrorNotification("最近 " + maxLookbackDays + " 天未找到可用 SEC master index。");
                System.out.println(msg);
                sendNotification("13D/13G 大股东披露异常", msg);
                return;
            }

            List<IndexFiling> schedule13Filings = parseSchedule13Idx(masterIndex.content);
            logDebug("Master index lookup returned " + schedule13Filings.size() + " Schedule 13D/13G candidate filings.");

            Map<String, List<Schedule13Entry>> alertsByTicker = new LinkedHashMap<>();
            int processedCount = 0;
            int matchedCount = 0;
            int failedCount = 0;

            for (IndexFiling filing : schedule13Filings) {
                try {
                    String filingText = downloadText(filing.url);
                    processedCount++;

                    Schedule13Entry entry = parseSchedule13Filing(filingText, filing, subjectCikToTicker);
                    if (entry != null) {
                        matchedCount++;
                        alertsByTicker.computeIfAbsent(entry.ticker, k -> new ArrayList<>()).add(entry);
                        logDebug("Creating Schedule 13 alert: " + entry.ticker
                                + " " + entry.formType
                                + " filer=" + entry.filerName
                                + " percent=" + entry.percentOwned
                                + " shares=" + entry.sharesOwned);
                    }
                } catch (Exception ex) {
                    failedCount++;
                    System.err.println("Warning: failed to process Schedule 13 filing at "
                            + filing.url + " - " + ex.getMessage());
                }
            }

            if (alertsByTicker.isEmpty()) {
                String msg = buildNoSchedule13Notification(
                        tickers,
                        unmappedTickers,
                        maxLookbackDays,
                        schedule13Filings.size(),
                        processedCount,
                        failedCount
                );
                System.out.println(msg);
                sendNotification("13D/13G 大股东披露提醒", msg);
                return;
            }

            String msg = buildSchedule13Notification(
                    alertsByTicker,
                    maxLookbackDays,
                    schedule13Filings.size(),
                    processedCount,
                    matchedCount,
                    failedCount,
                    unmappedTickers
            );

            boolean notified = sendNotification("13D/13G 大股东披露提醒", msg);
            System.out.println("Found " + matchedCount + " Schedule 13 alert(s) in "
                    + alertsByTicker.size() + " ticker(s). Notification sent: " + notified);

            if (!notified) {
                System.out.println(msg);
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            sendNotification("13D/13G 大股东披露异常", buildErrorNotification(e.getMessage()));
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

    private static class Schedule13Entry {
        final String ticker;
        final String formType;
        final String filerName;
        final String filingDate;
        final String subjectCik;
        final String sharesOwned;
        final String percentOwned;
        final String url;

        Schedule13Entry(String ticker, String formType, String filerName, String filingDate,
                        String subjectCik, String sharesOwned, String percentOwned, String url) {
            this.ticker = ticker;
            this.formType = formType;
            this.filerName = filerName;
            this.filingDate = filingDate;
            this.subjectCik = subjectCik;
            this.sharesOwned = sharesOwned;
            this.percentOwned = percentOwned;
            this.url = url;
        }
    }

    private static Schedule13Entry parseSchedule13Filing(
            String rawText,
            IndexFiling filing,
            Map<String, String> subjectCikToTicker) {

        if (rawText == null || rawText.isBlank()) {
            return null;
        }

        String subjectCik = extractSubjectCompanyCik(rawText);
        String normalizedSubjectCik = normalizeCik(subjectCik);
        String ticker = subjectCikToTicker.get(normalizedSubjectCik);

        if (ticker == null || ticker.isBlank()) {
            return null;
        }

        String formType = firstNonBlank(
                extractByRegex(rawText, "(?im)^\\s*CONFORMED SUBMISSION TYPE:\\s*([^\\r\\n]+)"),
                filing.formType,
                "Schedule 13D/13G"
        );

        String filingDate = firstNonBlank(
                extractByRegex(rawText, "(?im)^\\s*FILED AS OF DATE:\\s*([0-9]{8})"),
                filing.filingDate
        );

        String filerName = firstNonBlank(
                extractFiledByName(rawText),
                filing.filerName,
                "Unknown Filer"
        );

        String sharesOwned = extractSchedule13Shares(rawText);
        String percentOwned = extractSchedule13Percent(rawText);

        return new Schedule13Entry(
                ticker,
                formType.trim(),
                filerName.trim(),
                filingDate == null ? "" : filingDate.trim(),
                normalizedSubjectCik,
                sharesOwned,
                percentOwned,
                filing.url
        );
    }

    private static String extractSubjectCompanyCik(String rawText) {
        String subject = extractByRegex(rawText,
                "(?is)SUBJECT COMPANY:.*?CENTRAL INDEX KEY:\\s*([0-9]+)");
        if (!subject.isBlank()) {
            return subject;
        }

        String issuer = extractByRegex(rawText,
                "(?is)ISSUER:.*?CENTRAL INDEX KEY:\\s*([0-9]+)");
        if (!issuer.isBlank()) {
            return issuer;
        }

        return "";
    }

    private static String extractFiledByName(String rawText) {
        String filedBy = extractByRegex(rawText,
                "(?is)FILED BY:.*?COMPANY CONFORMED NAME:\\s*([^\\r\\n]+)");
        if (!filedBy.isBlank()) {
            return filedBy;
        }

        String owner = extractByRegex(rawText,
                "(?is)REPORTING PERSONS?[^A-Za-z0-9]{0,80}([A-Za-z0-9 .,&'\\-]{3,120})");
        if (!owner.isBlank()) {
            return cleanText(owner);
        }

        return "";
    }

    private static String extractSchedule13Percent(String rawText) {
        String compact = compactText(rawText);

        List<Pattern> patterns = List.of(
                Pattern.compile("(?is)Percent of Class.{0,300}?([0-9]+(?:\\.[0-9]+)?)\\s*%"),
                Pattern.compile("(?is)PERCENT OF CLASS.{0,300}?([0-9]+(?:\\.[0-9]+)?)\\s*%"),
                Pattern.compile("(?is)ROW \\(11\\).{0,300}?([0-9]+(?:\\.[0-9]+)?)\\s*%"),
                Pattern.compile("(?is)Item\\s*11.{0,300}?([0-9]+(?:\\.[0-9]+)?)\\s*%")
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(compact);
            if (matcher.find()) {
                return matcher.group(1).trim() + "%";
            }
        }

        return "";
    }

    private static String extractSchedule13Shares(String rawText) {
        String compact = compactText(rawText);

        List<Pattern> patterns = List.of(
                Pattern.compile("(?is)Aggregate Amount Beneficially Owned.{0,300}?([0-9][0-9,]*)"),
                Pattern.compile("(?is)AGGREGATE AMOUNT BENEFICIALLY OWNED.{0,300}?([0-9][0-9,]*)"),
                Pattern.compile("(?is)ROW \\(9\\).{0,300}?([0-9][0-9,]*)"),
                Pattern.compile("(?is)Item\\s*9.{0,300}?([0-9][0-9,]*)")
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(compact);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }

        return "";
    }

    private static List<IndexFiling> parseSchedule13Idx(String content) {
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

            String filerCik = normalizeCik(parts[0].trim());
            String filerName = parts[1].trim();
            String formType = parts[2].trim();
            String filingDate = parts[3].trim();
            String filename = parts[4].trim();

            if (!isSchedule13FormType(formType)) {
                continue;
            }

            if (filename.isEmpty()) {
                continue;
            }

            String url = SEC_BASE + filename;
            if (seenUrls.add(url)) {
                filings.add(new IndexFiling(filerCik, filerName, formType, filingDate, url));
            }
        }

        return filings;
    }

    private static boolean isSchedule13FormType(String formType) {
        if (formType == null) {
            return false;
        }

        String upper = formType.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return upper.equals("SC 13D")
                || upper.equals("SC 13D/A")
                || upper.equals("SC 13G")
                || upper.equals("SC 13G/A")
                || upper.equals("SC13D")
                || upper.equals("SC13D/A")
                || upper.equals("SC13G")
                || upper.equals("SC13G/A");
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
                    combinedContent.append('\n');
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
                int status = response.statusCode();

                if (status == 200) {
                    return response.body();
                }

                if (status == 403 || status == 404) {
                    throw new IllegalStateException("HTTP " + status + " for " + url);
                }

                lastException = new IllegalStateException("HTTP " + status + " for " + url + " attempt " + attempt);
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

        throw lastException != null
                ? lastException
                : new IllegalStateException("Failed to download " + url + " after 3 attempts");
    }

    private static String buildSchedule13Notification(
            Map<String, List<Schedule13Entry>> alertsByTicker,
            int lookbackDays,
            int candidateCount,
            int processedCount,
            int matchedCount,
            int failedCount,
            List<String> unmappedTickers) {

        final String br = "  \n";
        StringBuilder msg = new StringBuilder();

        msg.append("🏦 **13D/13G 大股东披露提醒**\n\n");
        msg.append("🔎 扫描范围：最近 ").append(lookbackDays).append(" 天").append(br);
        msg.append("📄 候选 13D/13G：").append(candidateCount).append(" 份").append(br);
        msg.append("📄 已处理：").append(processedCount).append(" 份").append(br);
        msg.append("📊 命中：").append(alertsByTicker.size()).append(" 个股票，")
                .append(matchedCount).append(" 份披露").append(br);

        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append(" 份").append(br);
        }

        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append(br);
        }

        msg.append('\n');

        for (Map.Entry<String, List<Schedule13Entry>> tickerEntry : alertsByTicker.entrySet()) {
            msg.append("## ").append(tickerEntry.getKey()).append('\n');

            for (Schedule13Entry entry : tickerEntry.getValue()) {
                msg.append("**").append(safeText(entry.formType, "Schedule 13D/13G")).append("**").append(br);
                msg.append("申报方：").append(safeText(entry.filerName, "Unknown Filer")).append(br);
                msg.append("披露日期：").append(formatDate(entry.filingDate)).append(br);

                if (entry.percentOwned != null && !entry.percentOwned.isBlank()) {
                    msg.append("持股比例：").append(entry.percentOwned).append(br);
                }

                if (entry.sharesOwned != null && !entry.sharesOwned.isBlank()) {
                    msg.append("持股数量：").append(entry.sharesOwned).append(br);
                }

                msg.append("SEC链接：").append(entry.url).append("\n\n");
            }
        }

        msg.append("说明：13D/13G 通常表示投资者或机构披露 5% 以上实益持股或其变动；13G 多为被动/合格机构披露，13D 通常更偏主动或可能影响公司治理的持股披露。");
        return msg.toString().trim();
    }

    private static String buildNoSchedule13Notification(
            String[] tickers,
            List<String> unmappedTickers,
            int lookbackDays,
            int candidateCount,
            int processedCount,
            int failedCount) {

        StringBuilder msg = new StringBuilder();
        msg.append("📭 **13D/13G 大股东披露扫描完成，暂无命中**\n\n");
        msg.append("🔎 扫描股票：").append(String.join(", ", tickers)).append('\n');
        msg.append("📆 扫描范围：最近 ").append(lookbackDays).append(" 天\n");
        msg.append("📄 候选 13D/13G：").append(candidateCount).append(" 份\n");
        msg.append("📄 已处理：").append(processedCount).append(" 份\n");

        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append(" 份\n");
        }

        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append('\n');
        }

        msg.append("\n结果：未发现匹配这些股票的 SC 13D / SC 13G 披露。");
        return msg.toString().trim();
    }

    private static String buildConfigNotification(String title, String detail) {
        return "⚠️ **13D/13G 机器人配置提醒**\n\n"
                + "问题：" + safeText(title, "配置异常") + "\n"
                + "说明：" + safeText(detail, "请检查启动参数和环境变量。");
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
        StringBuilder msg = new StringBuilder();
        msg.append("🚨 **13D/13G 机器人运行异常**\n\n");
        msg.append("错误信息：\n");
        msg.append("> ").append(safeText(errorMessage, "Unknown error")).append("\n\n");
        msg.append("建议检查：\n");
        msg.append("- SEC 网络访问是否正常\n");
        msg.append("- SEC_USER_AGENT / SEC_CONTACT_EMAIL 是否配置\n");
        msg.append("- DING_WEBHOOK_URL 或 DISCORD_WEBHOOK_URL 是否有效\n");
        msg.append("- 股票代码或 CIK 映射是否正确\n");
        msg.append("- 13D/13G 正文格式是否与当前解析逻辑兼容");
        return msg.toString().trim();
    }

    private static boolean sendNotification(String title, String message) {
        String dingTalkUrl = System.getenv("DING_WEBHOOK_URL");
        if (dingTalkUrl != null && !dingTalkUrl.isBlank()) {
            String dingTalkSecret = System.getenv("DING_WEBHOOK_SIGN");
            return sendDingTalkWebhook(dingTalkUrl, dingTalkSecret, title, message);
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
            String fullBody = "**" + title + "**\n" + message;
            return sendSingleDiscordMessage(webhookUrl, truncateForDiscord(fullBody));
        } catch (Exception e) {
            System.err.println("Warning: failed to send Discord notification: " + e.getMessage());
            return false;
        }
    }

    private static boolean sendSingleDiscordMessage(String webhookUrl, String content) throws Exception {
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
    }

    private static String truncateForDiscord(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= 1900) {
            return value;
        }
        return value.substring(0, 1900) + "\n...(内容过长已截断)";
    }

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
        if (ticker == null) {
            return "";
        }
        return ticker.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String normalizeCik(String cik) {
        if (cik == null) {
            return "";
        }
        return cik.trim().replaceFirst("^0+(?!$)", "");
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
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String compactText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String extractByRegex(String text, String regex) {
        if (text == null) {
            return "";
        }
        Matcher matcher = Pattern.compile(regex).matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
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
