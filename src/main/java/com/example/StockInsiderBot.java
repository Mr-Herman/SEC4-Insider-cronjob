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
            Map.entry("STZ", "0001593873"));

    // 职位翻译映射表
    private static final Map<String, String> POSITION_TRANSLATIONS = new HashMap<>();
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

    private static String translatePosition(String eng) {
        if (eng == null || eng.isBlank()) return eng;
        String lower = eng.toLowerCase(Locale.ROOT);
        // 精确匹配
        String trans = POSITION_TRANSLATIONS.get(lower);
        if (trans != null) return trans;
        // 尝试模糊匹配（包含关键词）
        for (Map.Entry<String, String> entry : POSITION_TRANSLATIONS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return eng; // 保留原文
    }

    public static void main(String[] args) {
        try {
            Map<String, String> options = parseOptions(args);
            String tickersArg = firstNonBlank(options.get("tickers"), System.getenv("TICKERS"),
                    options.get("positional"));
            long minimumUsd = parseLong(firstNonBlank(options.get("threshold"), System.getenv("THRESHOLD_USD")),
                    DEFAULT_MINIMUM_USD);
            int maxLookbackDays = parseInt(firstNonBlank(options.get("lookback"), System.getenv("LOOKBACK_DAYS")),
                    DEFAULT_MAX_LOOKBACK_DAYS);
            boolean debug = parseBoolean(firstNonBlank(options.get("debug"), System.getenv("DEBUG")), DEFAULT_DEBUG);
            setDebug(debug);
            logDebug("Debug mode enabled: " + debug);
            logDebug("Tickers: " + tickersArg);
            logDebug("Threshold: " + minimumUsd);
            logDebug("Lookback days: " + maxLookbackDays);

            if (tickersArg == null || tickersArg.isBlank()) {
                System.out.println("No tickers provided. Use --tickers=... or TICKERS env.");
                return;
            }

            String[] tickers = parseTickers(tickersArg);
            if (tickers.length == 0) {
                System.out.println("No valid tickers found in input.");
                return;
            }

            Map<String, String> tickerToCik = downloadTickerMapping();
            if (tickerToCik.isEmpty()) {
                System.err.println("Failed to download SEC ticker mapping.");
                return;
            }

            Map<String, String> cikToRequestedTicker = new HashMap<>();
            Set<String> ciks = new HashSet<>();
            for (String ticker : tickers) {
                String cik = findCikForTicker(ticker, tickerToCik);
                if (cik != null) {
                    String normalizedCik = cik.replaceFirst("^0+(?!$)", "");
                    ciks.add(normalizedCik);
                    cikToRequestedTicker.put(normalizedCik, ticker);
                    logDebug("Ticker mapped: " + ticker + " -> " + normalizedCik);
                } else {
                    System.err.println("Warning: ticker not found in SEC mapping: " + ticker);
                }
            }

            if (ciks.isEmpty()) {
                System.err.println("No valid CIKs found for provided tickers.");
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
                String msg = "No Form 4 filings found for " + String.join(", ", tickers) + " in the last "
                        + maxLookbackDays + " days.";
                System.out.println(msg);
                sendNotification(buildMissingNotification(tickers, "未找到内幕交易报告"));
                return;
            }

            Map<String, List<AlertEntry>> allAlerts = new LinkedHashMap<>();
            Set<String> tickersWithForm4 = new HashSet<>();
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
                throw new Exception("Failed to process any of the " + failedCount + " Form 4 filings found.");
            }

            Map<String, List<AlertEntry>> filteredAlerts = new LinkedHashMap<>();
            for (String ticker : tickers) {
                if (allAlerts.containsKey(ticker) && !allAlerts.get(ticker).isEmpty()) {
                    filteredAlerts.put(ticker, allAlerts.get(ticker));
                }
            }

            if (filteredAlerts.isEmpty()) {
                String noTradeMsg = "📭 今日未发现内幕交易。";
                System.out.println(noTradeMsg);
                sendNotification(noTradeMsg);
                return;
            }

            String message = buildGroupedNotification(filteredAlerts,
                    masterIndex != null ? masterIndex.indexDate : LocalDate.now().toString());
            boolean notified = sendNotification(message);
            System.out
                    .println("Found " + filteredAlerts.values().stream().mapToInt(List::size).sum() + " alert(s) in " +
                            filteredAlerts.size() + " ticker(s). Notification sent: " + notified);
            if (!notified) {
                System.out.println(message);
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (!errorMsg.contains("No Form 4 filings found") &&
                    !errorMsg.contains("No large insider transactions found") &&
                    !errorMsg.contains("No valid CIKs found")) {
                sendErrorNotification("内部人交易机器人错误: " + errorMsg);
            }
            System.exit(1);
        }
    }

    private static class AlertEntry {
        final String ownerName;
        final String position;
        final String type;
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
            this.shares = shares;
            this.price = price;
            this.amount = amount;
            this.is10b51 = is10b51;
            this.transactionDate = transactionDate;
            this.sharesOwnedAfter = sharesOwnedAfter;
        }
    }

    private static String formatDate(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.length() != 8) return yyyyMMdd;
        try {
            return yyyyMMdd.substring(0,4) + "年" + yyyyMMdd.substring(4,6) + "月" + yyyyMMdd.substring(6,8) + "日";
        } catch (Exception e) {
            return yyyyMMdd;
        }
    }

    private static String buildGroupedNotification(Map<String, List<AlertEntry>> alertsByTicker, String indexDate) {
        StringBuilder msg = new StringBuilder();
        msg.append("🔔 内部人交易警报 (").append(formatDate(indexDate)).append(")\n\n");

        boolean isFirstTicker = true;
        for (Map.Entry<String, List<AlertEntry>> entry : alertsByTicker.entrySet()) {
            String ticker = entry.getKey();
            List<AlertEntry> entries = entry.getValue();

            if (!isFirstTicker) {
                msg.append("─────────────────────────────────\n\n");
            }
            isFirstTicker = false;

            for (AlertEntry e : entries) {
                String planIcon = e.is10b51 ? " 🏷️[10b5-1]" : "";
                String date = e.transactionDate.isEmpty() ? "N/A" : formatDate(e.transactionDate.replace("-", ""));
                String sharesStr = formatNumber(e.shares);
                String amountStr = formatAmount(e.amount);
                String positionStr = e.sharesOwnedAfter > 0 ? formatNumber(e.sharesOwnedAfter) : "N/A";
                
                String actionIcon;
                if (e.type.equals("BUY")) {
                    actionIcon = "📈 买入";
                } else {
                    actionIcon = "📉 卖出";
                }
                if (e.type.equals("BUY")) {
                    msg.append("🔴 ");
                }
                msg.append("**").append(ticker).append("** · ")
                        .append(actionIcon).append(" · **")
                        .append(amountStr).append("**\n");

                msg.append("  ").append(date).append(" · ").append(e.ownerName).append("\n");

                String translatedPos = translatePosition(e.position);
                msg.append("  ").append(translatedPos);
                if (!planIcon.isEmpty()) {
                    msg.append(planIcon);
                }
                msg.append("\n");

                msg.append("  ").append(sharesStr).append(" 股 @ **$")
                        .append(String.format("%,.2f", e.price))
                        .append("** · 持股后 ").append(positionStr).append("\n\n");
            }
        }

        return msg.toString().trim();
    }

    private static String formatNumber(long num) {
        if (num >= 1_000_000)
            return String.format("%.1fM", num / 1_000_000.0);
        if (num >= 1_000)
            return String.format("%.1fK", num / 1_000.0);
        return Long.toString(num);
    }

    private static String formatAmount(double amount) {
        if (amount >= 1_000_000)
            return String.format("$%.1fM", amount / 1_000_000.0);
        if (amount >= 1_000)
            return String.format("$%.1fK", amount / 1_000.0);
        return String.format("$%.0f", amount);
    }

    private static String buildMissingNotification(String[] tickers, String reason) {
        StringBuilder msg = new StringBuilder();
        msg.append("🔔 内部人交易警报\n\n");
        for (String ticker : tickers) {
            msg.append("▶ ").append(ticker).append("\n  ").append(reason).append("\n\n");
        }
        return msg.toString().trim();
    }

    // 以下方法保持不变（parseOptions, firstNonBlank, logDebug, downloadTickerMapping...）
    // 为了节省篇幅，只列出关键新增方法，实际使用时请确保全部代码完整。
    // 由于篇幅限制，我将提供一个完整的可替换文件，请从下一行开始复制完整代码。
