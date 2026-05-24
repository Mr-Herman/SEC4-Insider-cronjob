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

            String tickersArg = firstNonBlank(
                    options.get("tickers"),
                    System.getenv("TICKERS"),
                    options.get("positional")
            );

            int lookbackDays = parseInt(
                    firstNonBlank(options.get("lookback"), System.getenv("LOOKBACK_DAYS")),
                    DEFAULT_MAX_LOOKBACK_DAYS
            );

            boolean debug = parseBoolean(
                    firstNonBlank(options.get("debug"), System.getenv("DEBUG")),
                    DEFAULT_DEBUG
            );

            setDebug(debug);
            logDebug("Form144 debug mode enabled: " + debug);
            logDebug("Tickers: " + tickersArg);
            logDebug("Lookback days: " + lookbackDays);

            if (tickersArg == null || tickersArg.isBlank()) {
                String msg = buildConfigNotification("未提供股票代码", "请配置 TICKERS 环境变量。");
                System.out.println(msg);
                sendNotification("Form 144 关联方计划卖出提醒", msg);
                return;
            }

            String[] tickers = parseTickers(tickersArg);
            if (tickers.length == 0) {
                String msg = buildConfigNotification("股票代码无效", "输入内容中没有解析到有效股票代码。");
                System.out.println(msg);
                sendNotification("Form 144 关联方计划卖出提醒", msg);
                return;
            }

            Map<String, String> tickerToCik = downloadTickerMapping();
            if (tickerToCik.isEmpty()) {
                String msg = buildErrorNotification("下载 SEC ticker mapping 失败，且 fallback 映射表为空。");
                System.err.println(msg);
                sendNotification("Form 144 关联方计划卖出提醒", msg);
                return;
            }

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
                sendNotification("Form 144 关联方计划卖出提醒", msg);
                return;
            }

            MasterIndex masterIndex = findMasterIndex(LocalDate.now(ZoneId.of("America/New_York")), lookbackDays);
            if (masterIndex == null) {
                String msg = buildErrorNotification("最近 " + lookbackDays + " 天未找到可用 SEC master index。");
                System.out.println(msg);
                sendNotification("Form 144 关联方计划卖出提醒", msg);
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
                        logDebug("Creating Form144 alert: " + entry.ticker
                                + " filer=" + entry.sellerName
                                + " shares=" + entry.sharesToSell
                                + " value=" + entry.marketValue);
                    }
                } catch (Exception ex) {
                    failedCount++;
                    System.err.println("Warning: failed to process Form 144 at " + filing.url + " - " + ex.getMessage());
                }
            }

            String message;
            if (alertsByTicker.isEmpty()) {
                message = buildNoAlertNotification(
                        tickers,
                        unmappedTickers,
                        lookbackDays,
                        candidates.size(),
                        processedCount,
                        failedCount
                );
            } else {
                message = buildForm144Notification(
                        alertsByTicker,
                        masterIndex.indexDate,
                        lookbackDays,
                        processedCount,
                        matchedCount,
                        failedCount,
                        unmappedTickers
                );
            }

            boolean notified = sendNotification("Form 144 关联方计划卖出提醒", message);
            System.out.println("Found " + matchedCount + " Form 144 alert(s) in " + alertsByTicker.size()
                    + " ticker(s). Notification sent: " + notified);

            if (!notified) {
                System.out.println(message);
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();

            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            sendNotification("Form 144 关联方计划卖出提醒", buildErrorNotification(errorMsg));
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

        Form144Entry(String ticker, String sellerName, String relationship, String filingDate,
                     String approximateSaleDate, String sharesToSell, String marketValue,
                     String securitiesTitle, String riskLevel, String analysis) {
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
        }
    }

    private static class Parsed144Fields {
        String sellerName = "";
        String relationship = "";
        String securitiesTitle = "";
        String sharesToSell = "";
        String marketValue = "";
        String approximateSaleDate = "";
    }

    private static Form144Entry parseForm144Filing(
            String rawText,
            IndexFiling filing,
            Map<String, String> issuerCikToTicker) {

        if (rawText == null || rawText.isBlank()) {
            return null;
        }

        String issuerCik = extractIssuerCik(rawText);
        String ticker = issuerCikToTicker.get(normalizeCik(issuerCik));

        if (ticker == null || ticker.isBlank()) {
            logDebug("Form 144 not matched. issuerCik=" + normalizeCik(issuerCik) + " url=" + filing.url);
            return null;
        }

        String compact = compactText(rawText);
        String lower = compact.toLowerCase(Locale.ROOT);

        if (isTaxWithholdingOrSellToCover(lower)) {
            logDebug("Skipping Form 144: tax withholding / sell-to-cover detected. url=" + filing.url);
            return null;
        }

        Parsed144Fields parsed = parseStructured144Fields(compact);

        String filingDate = firstNonBlank(
                extractByRegex(rawText, "(?im)^\\s*FILED AS OF DATE:\\s*([0-9]{8})"),
                filing.filingDate
        );

        String sellerName = firstNonBlank(
                parsed.sellerName,
                extractByRegex(rawText, "(?is)REPORTING-OWNER:.*?COMPANY CONFORMED NAME:\\s*([^\\r\\n]+)"),
                extractByRegex(rawText, "(?is)FILED BY:.*?COMPANY CONFORMED NAME:\\s*([^\\r\\n]+)"),
                filing.filerName,
                "Unknown Seller"
        );

        String relationship = firstNonBlank(parsed.relationship, "高管 / 关联方");
        String securitiesTitle = firstNonBlank(parsed.securitiesTitle, "Common Stock");
        String sharesToSell = firstNonBlank(parsed.sharesToSell, "");
        String marketValue = firstNonBlank(parsed.marketValue, "");
        String approximateSaleDate = firstNonBlank(parsed.approximateSaleDate, "N/A");

        if (sharesToSell.isBlank() && marketValue.isBlank()) {
            logDebug("Skipping Form 144: cannot parse shares/value. url=" + filing.url);
            return null;
        }

        String formattedShares = formatNumberText(sharesToSell);
        String formattedMarketValue = formatMoney(marketValue);
        String riskLevel = estimateRiskLevel(sharesToSell, marketValue);
        String analysis = buildAnalysis(riskLevel);

        return new Form144Entry(
                ticker,
                cleanText(sellerName),
                translateRelationship(relationship),
                filingDate == null ? "" : filingDate.trim(),
                formatPossibleUsDate(approximateSaleDate),
                formattedShares,
                formattedMarketValue,
                cleanText(securitiesTitle),
                riskLevel,
                analysis
        );
    }

    private static Parsed144Fields parseStructured144Fields(String compact) {
        Parsed144Fields f = new Parsed144Fields();

        Matcher rowMatcher = Pattern.compile(
                "(?is)\\bLIVE\\s+[0-9]{1,10}\\s+"
                        + "([A-Z][A-Za-z .,'-]{2,90})\\s+"
                        + ".{0,240}?\\b(Officer|Director|Affiliate|10% Owner|Executive|CEO|CFO|President|Vice President|Chief [A-Za-z ]{3,50})\\b\\s+"
                        + "([^\\n\\r]{0,100}?(?:Common Stock|Class A Common Stock|Class B Common Stock|Ordinary Shares|common)[^\\n\\r]{0,80}?)\\s+"
                        + "[^\\n\\r]{0,160}?\\s+"
                        + "([0-9][0-9,]*)\\s+"
                        + "([0-9][0-9,]*(?:\\.[0-9]+)?)\\s+"
                        + "[0-9][0-9,]*\\s+"
                        + "([0-9]{1,2}/[0-9]{1,2}/[0-9]{4})\\s+"
                        + "(?:NYSE|NASDAQ|Nasdaq|NYSE American|OTC|Cboe|AMEX)",
                Pattern.CASE_INSENSITIVE
        ).matcher(compact);

        if (rowMatcher.find()) {
            f.sellerName = cleanText(rowMatcher.group(1));
            f.relationship = cleanText(rowMatcher.group(2));
            f.securitiesTitle = cleanSecurityTitle(rowMatcher.group(3));
            f.sharesToSell = cleanText(rowMatcher.group(4));
            f.marketValue = cleanText(rowMatcher.group(5));
            f.approximateSaleDate = cleanText(rowMatcher.group(6));
            return f;
        }

        Matcher numberMatcher = Pattern.compile(
                "(?is)\\b([0-9][0-9,]*)\\s+([0-9][0-9,]*(?:\\.[0-9]+)?)\\s+[0-9][0-9,]*\\s+([0-9]{1,2}/[0-9]{1,2}/[0-9]{4})\\s+(?:NYSE|NASDAQ|Nasdaq|NYSE American|OTC|Cboe|AMEX)"
        ).matcher(compact);

        if (numberMatcher.find()) {
            f.sharesToSell = cleanText(numberMatcher.group(1));
            f.marketValue = cleanText(numberMatcher.group(2));
            f.approximateSaleDate = cleanText(numberMatcher.group(3));
        }

        f.sellerName = firstNonBlank(
                extractByRegex(compact, "(?is)\\b([A-Z][A-Za-z .,'-]{2,80})\\s+(Officer|Director|Affiliate|10% Owner)\\s+[^\\n\\r]{0,80}(?:Common Stock|common)\\b"),
                ""
        );

        f.relationship = firstNonBlank(
                extractByRegex(compact, "(?is)\\b(Officer|Director|Affiliate|10% Owner|Executive|CEO|CFO|President|Vice President|Chief [A-Za-z ]{3,50})\\b"),
                ""
        );

        f.securitiesTitle = firstNonBlank(
                extractByRegex(compact, "(?is)\\b(Common Stock|Class A Common Stock|Class B Common Stock|Ordinary Shares|common)\\b"),
                ""
        );

        return f;
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

        String issuerXml = extractByRegex(rawText, "(?is)<issuerCik>\\s*([0-9]+)\\s*</issuerCik>");
        if (!issuerXml.isBlank()) {
            return issuerXml;
        }

        return extractByRegex(rawText, "(?is)Name of Issuer[^0-9]{0,200}CIK[^0-9]{0,30}([0-9]+)");
    }

    private static boolean isTaxWithholdingOrSellToCover(String lower) {
        if (lower == null || lower.isBlank()) {
            return false;
        }

        return lower.contains("tax withholding")
                || lower.contains("taxes withheld")
                || lower.contains("withheld to satisfy")
                || lower.contains("withholding obligation")
                || lower.contains("tax liability")
                || lower.contains("payment of tax")
                || lower.contains("payment of taxes")
                || lower.contains("sell to cover")
                || lower.contains("sold to cover")
                || lower.contains("cover tax")
                || lower.contains("cover taxes")
                || lower.contains("satisfy tax")
                || lower.contains("satisfy withholding")
                || lower.contains("net settlement")
                || lower.contains("net settled")
                || lower.contains("to satisfy tax obligations")
                || lower.contains("to cover withholding taxes");
    }

    private static String buildForm144Notification(
            Map<String, List<Form144Entry>> alertsByTicker,
            String indexDate,
            int lookbackDays,
            int processedCount,
            int matchedCount,
            int failedCount,
            List<String> unmappedTickers) {

        final String br = "  \n";
        StringBuilder msg = new StringBuilder();

        msg.append("📅 披露日期：").append(formatDate(indexDate)).append(br);
        msg.append("🔎 扫描范围：最近 ").append(lookbackDays).append(" 天").append(br);
        msg.append("📄 已处理 Form 144：").append(processedCount).append(" 份").append(br);
        msg.append("📊 命中结果：").append(alertsByTicker.size()).append(" 个股票，").append(matchedCount).append(" 份计划卖出").append(br);

        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append(" 份").append(br);
        }

        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append(br);
        }

        msg.append("\n");

        for (Map.Entry<String, List<Form144Entry>> tickerEntry : alertsByTicker.entrySet()) {
            msg.append("**").append(tickerEntry.getKey()).append("**").append("\n\n");

            for (Form144Entry entry : tickerEntry.getValue()) {
                msg.append("⚠️ 关联方计划卖出\n\n");
                msg.append("申报人：").append(safeText(entry.sellerName, "Unknown Seller")).append(br);
                msg.append("关系/职位：").append(safeText(entry.relationship, "高管 / 关联方")).append(br);
                msg.append("计划卖出数量：").append(safeText(entry.sharesToSell, "N/A")).append(" 股").append(br);
                msg.append("预计卖出金额：约 ").append(safeText(entry.marketValue, "N/A")).append(br);
                msg.append("计划卖出日期：").append(safeText(entry.approximateSaleDate, "N/A")).append(br);
                msg.append("证券类型：")
                        .append(safeText(entry.securitiesTitle, "Common Stock"))
                        .append(" ｜ 📌 分析判断：")
                        .append(safeText(entry.riskLevel, "N/A"))
                        .append("\n\n");
            }
        }

        return msg.toString().trim();
    }

    private static String buildNoAlertNotification(
            String[] tickers,
            List<String> unmappedTickers,
            int lookbackDays,
            int candidateCount,
            int processedCount,
            int failedCount) {

        StringBuilder msg = new StringBuilder();
        msg.append("📭 Form 144 扫描完成，暂无计划卖出提醒\n\n");
        msg.append("🔎 扫描股票：").append(String.join(", ", tickers)).append("\n");
        msg.append("📆 扫描范围：最近 ").append(lookbackDays).append(" 天\n");
        msg.append("📄 候选 Form 144：").append(candidateCount).append(" 份\n");
        msg.append("📄 已处理：").append(processedCount).append(" 份\n");

        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append(" 份\n");
        }

        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append("\n");
        }

        msg.append("\n结果：未发现匹配这些股票的有效 Form 144 计划卖出披露。");
        return msg.toString().trim();
    }

    private static String buildAnalysis(String riskLevel) {
        if (riskLevel.startsWith("高关注")) {
            return "金额较大，建议后续观察是否出现对应 Form 4 实际卖出";
        }

        if (riskLevel.startsWith("中性偏负面")) {
            return "金额达到一定规模，建议后续观察是否出现对应 Form 4 实际卖出";
        }

        return "单笔规模不大，单独参考价值有限";
    }

    private static String estimateRiskLevel(String sharesText, String valueText) {
        double value = parseMoney(valueText);
        long shares = parseLongSafely(sharesText);

        if (value >= 5_000_000 || shares >= 100_000) {
            return "高关注";
        }

        if (value >= 1_000_000 || shares >= 20_000) {
            return "中性偏负面";
        }

        return "低关注";
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
                    combinedContent.append(content).append("\n");
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

    private static List<IndexFiling> parseForm144Idx(String content) {
        List<IndexFiling> filings = new ArrayList<>();
        Set<String> seenAccessions = new LinkedHashSet<>();

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

            String filename = parts[4].trim();
            String accession = extractAccessionFromFilename(filename);
            if (!accession.isBlank() && !seenAccessions.add(accession)) {
                logDebug("Skipping duplicated Form 144 accession: " + accession);
                continue;
            }

            String url = SEC_BASE + filename;
            filings.add(new IndexFiling(
                    normalizeCik(parts[0]),
                    parts[1].trim(),
                    parts[2].trim(),
                    parts[3].trim(),
                    url
            ));
        }

        return filings;
    }

    private static String extractAccessionFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }

        Matcher matcher = Pattern.compile("([0-9]{10}-[0-9]{2}-[0-9]{6})").matcher(filename);
        if (matcher.find()) {
            return matcher.group(1);
        }

        Matcher noDash = Pattern.compile("([0-9]{18})").matcher(filename);
        if (noDash.find()) {
            String s = noDash.group(1);
            return s.substring(0, 10) + "-" + s.substring(10, 12) + "-" + s.substring(12);
        }

        return filename;
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

        throw lastException != null ? lastException : new IllegalStateException("Failed to download " + url);
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

    private static String compactText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String cleanText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String cleanSecurityTitle(String value) {
        String clean = cleanText(value);
        Matcher matcher = Pattern.compile("(?i)(Class A Common Stock|Class B Common Stock|Common Stock|Ordinary Shares|common)").matcher(clean);
        if (matcher.find()) {
            String title = matcher.group(1);
            if ("common".equalsIgnoreCase(title)) {
                return "Common Stock";
            }
            return title;
        }
        return clean;
    }

    private static String translateRelationship(String relationship) {
        if (relationship == null || relationship.isBlank()) {
            return "高管 / 关联方";
        }

        String lower = relationship.toLowerCase(Locale.ROOT);
        if (lower.contains("officer") || lower.contains("chief") || lower.contains("ceo")
                || lower.contains("cfo") || lower.contains("president") || lower.contains("vice president")) {
            return "高管 / 关联方";
        }
        if (lower.contains("director")) {
            return "董事 / 关联方";
        }
        if (lower.contains("10%")) {
            return "10% 大股东 / 关联方";
        }
        if (lower.contains("affiliate")) {
            return "关联方";
        }
        return cleanText(relationship);
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

    private static String formatPossibleUsDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank() || "N/A".equalsIgnoreCase(dateStr)) {
            return "N/A";
        }

        Matcher matcher = Pattern.compile("([0-9]{1,2})/([0-9]{1,2})/([0-9]{4})").matcher(dateStr.trim());
        if (matcher.matches()) {
            String month = matcher.group(1);
            String day = matcher.group(2);
            String year = matcher.group(3);
            return year + "年" + pad2(month) + "月" + pad2(day) + "日";
        }

        return dateStr;
    }

    private static String pad2(String value) {
        return value != null && value.length() == 1 ? "0" + value : value;
    }

    private static String formatNumberText(String value) {
        long num = parseLongSafely(value);
        if (num <= 0) {
            return cleanText(value);
        }
        return String.format("%,d", num);
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

    private static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String extractByRegex(String text, String regex) {
        if (text == null) {
            return "";
        }
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String buildConfigNotification(String title, String detail) {
        return "⚠️ Form 144 机器人配置提醒\n\n"
                + "问题：" + safeText(title, "配置异常") + "\n"
                + "说明：" + safeText(detail, "请检查启动参数和环境变量。");
    }

    private static String buildNoValidCikNotification(String[] tickers, List<String> unmappedTickers) {
        StringBuilder msg = new StringBuilder();
        msg.append("⚠️ 未找到有效 CIK\n\n");
        msg.append("输入股票：").append(String.join(", ", tickers)).append("\n");

        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("未映射股票：").append(String.join(", ", unmappedTickers)).append("\n");
        }

        msg.append("\n请检查股票代码是否正确，或补充 FALLBACK_TICKER_MAP。");
        return msg.toString().trim();
    }

    private static String buildErrorNotification(String errorMessage) {
        return "🚨 Form 144 机器人运行异常\n\n"
                + "错误信息：\n> " + safeText(errorMessage, "Unknown error")
                + "\n\n建议检查：\n"
                + "- SEC 网络访问是否正常\n"
                + "- SEC_USER_AGENT / SEC_CONTACT_EMAIL 是否配置\n"
                + "- DING_WEBHOOK_URL 或 DISCORD_WEBHOOK_URL 是否有效\n"
                + "- Form 144 正文格式是否与当前解析逻辑兼容";
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
