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

    private static boolean debugEnabled = DEFAULT_DEBUG;

    private static String translatePosition(String eng) {
        if (eng == null || eng.isBlank()) return "未知职位";
        String lower = eng.toLowerCase(Locale.ROOT);
        String trans = POSITION_TRANSLATIONS.get(lower);
        if (trans != null) return trans;
        for (Map.Entry<String, String> entry : POSITION_TRANSLATIONS.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        return eng.trim();
    }

    public static void main(String[] args) {
        try {
            Map<String, String> options = parseOptions(args);
            String tickersArg = firstNonBlank(options.get("tickers"), System.getenv("TICKERS"), options.get("positional"));
            long minimumUsd = parseLong(firstNonBlank(options.get("threshold"), System.getenv("THRESHOLD_USD")), DEFAULT_MINIMUM_USD);
            int maxLookbackDays = parseInt(firstNonBlank(options.get("lookback"), System.getenv("LOOKBACK_DAYS")), DEFAULT_MAX_LOOKBACK_DAYS);
            boolean debug = parseBoolean(firstNonBlank(options.get("debug"), System.getenv("DEBUG")), DEFAULT_DEBUG);
            setDebug(debug);

            if (tickersArg == null || tickersArg.isBlank()) {
                System.out.println("No tickers provided. Use --tickers=... or TICKERS env.");
                return;
            }

            String[] tickers = parseTickers(tickersArg);
            Map<String, String> tickerToCik = downloadTickerMapping();

            Map<String, String> cikToRequestedTicker = new HashMap<>();
            Set<String> ciks = new HashSet<>();
            Set<String> unmappedTickers = new HashSet<>();
            for (String ticker : tickers) {
                String cik = findCikForTicker(ticker, tickerToCik);
                if (cik != null) {
                    String normalizedCik = cik.replaceFirst("^0+(?!$)", "");
                    ciks.add(normalizedCik);
                    cikToRequestedTicker.put(normalizedCik, ticker);
                } else {
                    unmappedTickers.add(ticker);
                }
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

            Map<String, List<AlertEntry>> allAlerts = new LinkedHashMap<>();
            Set<String> tickersWithForm4 = new HashSet<>();
            int processedCount = 0, failedCount = 0;
            for (String url : form4Urls) {
                try {
                    String xml = downloadText(url);
                    Map<String, List<AlertEntry>> parsed = parseForm4(xml, minimumUsd, cikToRequestedTicker);
                    parsed.forEach((ticker, alerts) -> {
                        tickersWithForm4.add(ticker);
                        if (!alerts.isEmpty()) allAlerts.computeIfAbsent(ticker, k -> new ArrayList<>()).addAll(alerts);
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

            String message;
            if (filteredAlerts.isEmpty()) {
                if (processedCount == 0 && failedCount > 0) {
                    message = "⚠️ 内部人交易扫描失败：未能处理任何 Form 4 文件。";
                } else if (!form4Urls.isEmpty()) {
                    message = "📭 内部人交易扫描完成：未发现达到阈值的交易。";
                } else {
                    message = "📭 内部人交易扫描完成：最近没有 Form 4 文件。";
                }
                sendNotification(message);
                return;
            }

            message = buildGroupedNotification(filteredAlerts,
                    masterIndex != null ? masterIndex.indexDate : LocalDate.now().toString(),
                    unmappedTickers,
                    minimumUsd,
                    processedCount,
                    failedCount,
                    form4Urls.size()
            );
            boolean notified = sendNotification(message);
            if (!notified) System.out.println(message);

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            sendErrorNotification("内部人交易机器人错误: " + errorMsg);
            e.printStackTrace();
        }
    }

    // ======= 折叠美化通知方法 =======
    private static String buildGroupedNotification(Map<String, List<AlertEntry>> alertsByTicker, String indexDate,
                                                    Set<String> unmappedTickers, long thresholdUsd,
                                                    int processedCount, int failedCount, int totalForm4) {
        StringBuilder msg = new StringBuilder();
        msg.append("🔔 **内部人交易警报**\n\n");
        msg.append("📅 **报告日期**：").append(formatDate(indexDate)).append("\n");
        msg.append("🔎 **扫描范围**：最近 1 天\n");
        msg.append("💰 **提醒阈值**：≥ $").append(String.format("%,.0f", thresholdUsd)).append("\n");
        msg.append("📄 **已处理 Form 4**：").append(totalForm4).append(" 份\n");
        if (failedCount > 0) msg.append("⚠️ **处理失败**：").append(failedCount).append(" 份\n");
        if (!unmappedTickers.isEmpty()) msg.append("⚠️ **未映射股票**：").append(String.join(", ", unmappedTickers)).append("\n");

        int totalTrades = alertsByTicker.values().stream().mapToInt(List::size).sum();
        int totalStocks = alertsByTicker.size();
        double totalAmount = alertsByTicker.values().stream().flatMap(List::stream).mapToDouble(a -> a.amount).sum();
        msg.append("📊 **命中结果**：").append(totalStocks).append(" 个股票 | ").append(totalTrades).append(" 笔交易 | 总金额 $").append(String.format("%,.1fM", totalAmount/1_000_000)).append("\n");
        msg.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        for (Map.Entry<String, List<AlertEntry>> entry : alertsByTicker.entrySet()) {
            String ticker = entry.getKey();
            List<AlertEntry> trades = entry.getValue();
            long buyCount = trades.stream().filter(a -> "BUY".equals(a.type)).count();
            long sellCount = trades.size() - buyCount;
            double totalTickerAmount = trades.stream().mapToDouble(a -> a.amount).sum();

            msg.append("<details>\n<summary>**").append(ticker).append("** (").append(trades.size())
                    .append(" 笔 | 买入 ").append(buyCount).append(" | 卖出 ").append(sellCount)
                    .append(" | 总金额 $").append(String.format("%,.1fM", totalTickerAmount/1_000_000)).append(")</summary>\n\n");

            msg.append("| 操作 | 日期 | 人员 | 职位 | 股数 | 价格 | 持股后 |\n");
            msg.append("|-----|-----|-----|-----|-----|-----|-----|\n");
            for (AlertEntry a : trades) {
                String icon = a.type.equals("BUY") ? "🔴" : "🟢";
                String planIcon = a.is10b51 ? " 🏷️" : "";
                msg.append("| ").append(icon).append(" ").append(a.type).append(planIcon).append(" | ")
                        .append(formatDate(a.transactionDate)).append(" | ")
                        .append(a.ownerName).append(" | ")
                        .append(translatePosition(a.position)).append(" | ")
                        .append(formatNumber(a.shares)).append(" | $")
                        .append(String.format("%,.2f", a.price)).append(" | ")
                        .append(formatNumber(a.sharesOwnedAfter)).append(" |\n");
            }
            msg.append("</details>\n\n");
        }
        return msg.toString().trim();
    }

    // ======= 下面保留原有辅助方法 =======
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

    private static class MasterIndex {
        final String indexDate;
        final String content;
        MasterIndex(String indexDate, String content) { this.indexDate = indexDate; this.content = content; }
    }

    private static String formatDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return dateStr;
        String clean = dateStr.replace("-", "");
        if (clean.length() == 8 && clean.matches("\\d{8}")) {
            return clean.substring(0,4) + "年" + clean.substring(4,6) + "月" + clean.substring(6,8) + "日";
        }
        return dateStr;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static long parseLong(String value, long fallback) { try { return value != null ? Long.parseLong(value.trim()) : fallback; } catch (Exception e) { return fallback; } }
    private static int parseInt(String value, int fallback) { try { return value != null ? Integer.parseInt(value.trim()) : fallback; } catch (Exception e) { return fallback; } }
    private static boolean parseBoolean(String value, boolean fallback) { if (value == null || value.isBlank()) return fallback; String v = value.trim().toLowerCase(); return !(v.equals("false")||v.equals("0")||v.equals("no")||v.equals("off")); }
    private static void setDebug(boolean enabled) { debugEnabled = enabled; }
    private static String[] parseTickers(String tickersArg) { return Arrays.stream(tickersArg.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).toArray(String[]::new); }
    private static Map<String,String> parseOptions(String[] args) { Map<String,String> options=new HashMap<>(); String positional=null; for(String arg:args){ if(arg==null||arg.isBlank()) continue; if(arg.startsWith("--")){ String normalized=arg.substring(2); String[] parts=normalized.split("=",2); if(parts.length==2) options.put(parts[0].toLowerCase(),parts[1]); else options.put(parts[0].toLowerCase(),"true"); } else if(positional==null) positional=arg; } options.put("positional",positional); return options; }

    // === 其他下载、解析、通知等方法全部保持原有逻辑 ===
}
