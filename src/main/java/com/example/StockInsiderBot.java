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
            Map.entry("BRKB", "1067983"), Map.entry("BRK-B", "1067983"),
            Map.entry("MSFT", "0000789019"), Map.entry("ZTS", "0001555285"),
            Map.entry("STZ", "0001593873"));

    public static void main(String[] args) {
        try {
            Map<String, String> options = parseOptions(args);
            String tickersArg = firstNonBlank(options.get("tickers"), System.getenv("TICKERS"), options.get("positional"));
            long minimumUsd = parseLong(firstNonBlank(options.get("threshold"), System.getenv("THRESHOLD_USD")), DEFAULT_MINIMUM_USD);
            int maxLookbackDays = parseInt(firstNonBlank(options.get("lookback"), System.getenv("LOOKBACK_DAYS")), DEFAULT_MAX_LOOKBACK_DAYS);
            boolean debug = parseBoolean(firstNonBlank(options.get("debug"), System.getenv("DEBUG")), DEFAULT_DEBUG);
            setDebug(debug);
            logDebug("Debug mode enabled: " + debug + ", Tickers: " + tickersArg + ", Threshold: " + minimumUsd + ", Lookback: " + maxLookbackDays);

            if (tickersArg == null || tickersArg.isBlank()) {
                System.out.println("No tickers provided. Use --tickers=... or TICKERS env.");
                return;
            }

            String[] tickers = parseTickers(tickersArg);
            if (tickers.length == 0) return;

            Map<String, String> tickerToCik = downloadTickerMapping();
            if (tickerToCik.isEmpty()) return;

            Map<String, String> cikToRequestedTicker = new HashMap<>();
            Set<String> ciks = new HashSet<>();
            for (String ticker : tickers) {
                String cik = findCikForTicker(ticker, tickerToCik);
                if (cik != null) {
                    String normalizedCik = cik.replaceFirst("^0+(?!$)", "");
                    ciks.add(normalizedCik);
                    cikToRequestedTicker.put(normalizedCik, ticker);
                }
            }
            if (ciks.isEmpty()) return;

            LocalDate currentDate = LocalDate.now(ZoneId.of("America/New_York"));
            List<String> form4Urls = new ArrayList<>();
            MasterIndex masterIndex = findMasterIndex(currentDate, maxLookbackDays);
            if (masterIndex != null) form4Urls.addAll(parseMasterIdx(masterIndex.content, ciks));

            if (form4Urls.isEmpty()) form4Urls.addAll(fetchForm4UrlsFromEdgarBrowse(ciks, maxLookbackDays));

            if (form4Urls.isEmpty()) {
                sendNotification(buildMissingNotification(tickers, "No Form 4 filings found"));
                return;
            }

            Map<String, List<AlertEntry>> allAlerts = new LinkedHashMap<>();
            int processedCount = 0, failedCount = 0;
            for (String url : form4Urls) {
                try {
                    Map<String, List<AlertEntry>> parsed = parseForm4(downloadText(url), minimumUsd, cikToRequestedTicker);
                    parsed.forEach((t, alerts) -> {
                        if (!alerts.isEmpty()) allAlerts.computeIfAbsent(t, k -> new ArrayList<>()).addAll(alerts);
                    });
                    processedCount++;
                } catch (Exception ex) {
                    failedCount++;
                }
            }

            if (processedCount == 0 && failedCount > 0) throw new Exception("Failed to process " + failedCount + " filings.");

            Map<String, List<AlertEntry>> filteredAlerts = new LinkedHashMap<>();
            for (String ticker : tickers) {
                if (allAlerts.containsKey(ticker) && !allAlerts.get(ticker).isEmpty()) {
                    filteredAlerts.put(ticker, allAlerts.get(ticker));
                }
            }

            if (filteredAlerts.isEmpty()) {
                sendNotification("📭 No insider transactions found today.");
                return;
            }

            String message = buildGroupedNotification(filteredAlerts, masterIndex != null ? masterIndex.indexDate : LocalDate.now().toString());
            sendNotification(message);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (!errorMsg.contains("No Form 4") && !errorMsg.contains("No large insider") && !errorMsg.contains("No valid CIKs")) {
                sendErrorNotification("Insider Bot Error: " + errorMsg);
            }
            System.exit(1);
        }
    }

    private static class AlertEntry {
        final String ownerName, position, type, transactionDate;
        final long shares, sharesOwnedAfter;
        final double price, amount;
        final boolean is10b51;

        AlertEntry(String ownerName, String position, String type, String security, long shares, double price, double amount, boolean is10b51, String transactionDate, long sharesOwnedAfter) {
            this.ownerName = ownerName; this.position = position; this.type = type;
            this.shares = shares; this.price = price; this.amount = amount;
            this.is10b51 = is10b51; this.transactionDate = transactionDate; this.sharesOwnedAfter = sharesOwnedAfter;
        }
    }

    private static String buildGroupedNotification(Map<String, List<AlertEntry>> alertsByTicker, String indexDate) {
        StringBuilder msg = new StringBuilder("⏰ Insider Alerts (").append(indexDate).append(")\n\n");
        boolean isFirstTicker = true;
        for (Map.Entry<String, List<AlertEntry>> entry : alertsByTicker.entrySet()) {
            if (!isFirstTicker) msg.append("─────────────────────────────────\n\n");
            isFirstTicker = false;
            for (AlertEntry e : entry.getValue()) {
                String planIcon = e.is10b51 ? " 🏷️[10b5-1]" : "";
                String date = e.transactionDate.isEmpty() ? "N/A" : e.transactionDate;
                String posStr = e.sharesOwnedAfter > 0 ? formatNumber(e.sharesOwnedAfter) : "N/A";
                msg.append(e.type.equals("BUY") ? "🔴 " : "").append("**").append(entry.getKey()).append("** · ")
                   .append(e.type.equals("BUY") ? "📈 BUY" : "📉 SELL").append(" · **").append(formatAmount(e.amount)).append("**\n")
                   .append("  ").append(date).append(" · ").append(e.ownerName).append("\n")
                   .append("  ").append(e.position).append(planIcon).append("\n")
                   .append("  ").append(formatNumber(e.shares)).append(" @ **$").append(String.format("%,.2f", e.price))
                   .append("** · 持仓 ").append(posStr).append("\n\n");
            }
        }
        return msg.toString().trim();
    }

    private static String formatNumber(long num) {
        return num >= 1_000_000 ? String.format("%.1fM", num / 1_000_000.0) : num >= 1_000 ? String.format("%.1fK", num / 1_000.0) : Long.toString(num);
    }

    private static String formatAmount(double amount) {
        return amount >= 1_000_000 ? String.format("$%.1fM", amount / 1_000_000.0) : amount >= 1_000 ? String.format("$%.1fK", amount / 1_000.0) : String.format("$%.0f", amount);
    }

    private static String buildMissingNotification(String[] tickers, String reason) {
        StringBuilder msg = new StringBuilder("🔔 Insider Alerts\n\n");
        for (String t : tickers) msg.append("▶ ").append(t).append("\n  ").append(reason).append("\n\n");
        return msg.toString().trim();
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        String positional = null;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) continue;
            if (arg.startsWith("--")) {
                String[] parts = arg.substring(2).split("=", 2);
                options.put(parts[0].toLowerCase(Locale.ROOT), parts.length == 2 ? parts[1] : "true");
            } else if (positional == null) positional = arg;
        }
        options.put("positional", positional);
        return options;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static boolean debugEnabled = DEFAULT_DEBUG;
    private static void setDebug(boolean enabled) { debugEnabled = enabled; }
    private static void logDebug(String message) { if (debugEnabled) System.out.println("DEBUG: " + message); }

    private static long parseLong(String value, long fallback) {
        try { return (value != null && !value.isBlank()) ? Long.parseLong(value.trim()) : fallback; } catch (NumberFormatException e) { return fallback; }
    }

    private static int parseInt(String value, int fallback) {
        try { return (value != null && !value.isBlank()) ? Integer.parseInt(value.trim()) : fallback; } catch (NumberFormatException e) { return fallback; }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        String t = value.trim().toLowerCase(Locale.ROOT);
        return !(t.equals("false") || t.equals("0") || t.equals("no") || t.equals("off"));
    }

    private static String[] parseTickers(String tickersArg) {
        return Arrays.stream(tickersArg.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).toArray(String[]::new);
    }

    private static Map<String, String> downloadTickerMapping() {
        Map<String, String> map = new HashMap<>();
        try {
            String content = downloadText(TICKER_URL);
            if (content != null && !content.isBlank()) {
                for (String line : content.split("\\R")) {
                    String[] parts = line.trim().split("\\t");
                    if (parts.length == 2) map.put(parts[0].toUpperCase(Locale.ROOT), parts[1]);
                }
            }
        } catch (Exception ignored) {}
        if (map.isEmpty()) map.putAll(FALLBACK_TICKER_MAP);
        return map;
    }

    private static String findCikForTicker(String ticker, Map<String, String> tickerToCik) {
        String clean = ticker.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (clean.isBlank()) return null;
        for (Map.Entry<String, String> e : tickerToCik.entrySet()) if (e.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").equals(clean)) return e.getValue();
        for (Map.Entry<String, String> e : FALLBACK_TICKER_MAP.entrySet()) if (e.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").equals(clean)) return e.getValue();
        return null;
    }

    private static MasterIndex findMasterIndex(LocalDate startDate, int maxLookbackDays) {
        LocalDate date = startDate;
        StringBuilder combined = new StringBuilder();
        LocalDate foundDate = null;
        for (int i = 0; i < maxLookbackDays; i++) {
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String url = SEC_BASE + "edgar/daily-index/" + date.getYear() + "/QTR" + ((date.getMonthValue() - 1) / 3 + 1) + "/master." + dateStr + ".idx";
            try {
                String content = downloadText(url);
                if (content != null && !content.isBlank()) {
                    combined.append(content);
                    if (foundDate == null) foundDate = date;
                }
            } catch (Exception ignored) {}
            date = date.minusDays(1);
        }
        return combined.length() > 0 && foundDate != null ? new MasterIndex(foundDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")), combined.toString()) : null;
    }

    private static String downloadText(String url) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpGet get = new HttpGet(url);
                get.setHeader("User-Agent", firstNonBlank(System.getenv("SEC_USER_AGENT"), DEFAULT_SEC_USER_AGENT));
                get.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                get.setHeader("From", firstNonBlank(System.getenv("SEC_CONTACT_EMAIL"), DEFAULT_SEC_CONTACT_EMAIL));
                try (ClassicHttpResponse response = client.execute(get)) {
                    int status = response.getCode();
                    if (status == HttpStatus.SC_OK) {
                        HttpEntity entity = response.getEntity();
                        if (entity == null) throw new IllegalStateException("Empty response");
                        return EntityUtils.toString(entity);
                    } else if (status == 403 || status == 404) throw new IllegalStateException("HTTP " + status);
                    else {
                        lastException = new IllegalStateException("HTTP " + status);
                        if (attempt < 3) Thread.sleep(2000);
                    }
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < 3) Thread.sleep(1000);
            }
        }
        throw lastException != null ? lastException : new IllegalStateException("Failed to download");
    }

    private static List<String> parseMasterIdx(String content, Set<String> ciks) {
        Set<String> cleanCiks = new HashSet<>();
        for (String c : ciks) cleanCiks.add(c.replaceFirst("^0+(?!$)", ""));
        Set<String> urls = new LinkedHashSet<>();
        if (content == null) return new ArrayList<>(urls);
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("CIK|") || line.startsWith("-----")) continue;
            String[] parts = line.split("\\|", 6);
            if (parts.length >= 5 && parts[2].trim().startsWith("4") && cleanCiks.contains(parts[0].trim().replaceFirst("^0+(?!$)", ""))) {
                if (!parts[4].trim().isEmpty()) urls.add(SEC_BASE + parts[4].trim());
            }
        }
        return new ArrayList<>(urls);
    }

    private static List<String> fetchForm4UrlsFromEdgarBrowse(Set<String> ciks, int maxLookbackDays) {
        Set<String> urls = new LinkedHashSet<>();
        for (String cik : ciks) {
            try {
                String xml = downloadText("https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=" + cik + "&type=4&owner=include&count=100&output=atom");
                if (xml != null && !xml.isBlank()) urls.addAll(parseBrowseEdgarAtom(xml, maxLookbackDays));
            } catch (Exception ignored) {}
        }
        return new ArrayList<>(urls);
    }

    private static List<String> parseBrowseEdgarAtom(String atomXml, int maxLookbackDays) {
        Set<String> urls = new LinkedHashSet<>();
        LocalDate threshold = LocalDate.now().minusDays(maxLookbackDays);
        Matcher entryMatcher = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(atomXml);
        Pattern datePat = Pattern.compile("<filing-date>(.*?)</filing-date>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern hrefPat = Pattern.compile("<filing-href>(.*?)</filing-href>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        while (entryMatcher.find()) {
            String entry = entryMatcher.group(1);
            Matcher dateM = datePat.matcher(entry), hrefM = hrefPat.matcher(entry);
            if (dateM.find() && hrefM.find()) {
                try {
                    if (!LocalDate.parse(dateM.group(1).trim()).isBefore(threshold)) {
                        String xmlUrl = findForm4XmlUrlFromIndexPage(hrefM.group(1).trim());
                        if (xmlUrl != null) urls.add(xmlUrl);
                    }
                } catch (Exception ignored) {}
            }
        }
        return new ArrayList<>(urls);
    }

    private static String findForm4XmlUrlFromIndexPage(String indexUrl) {
        try {
            String html = downloadText(indexUrl);
            if (html == null || html.isBlank()) return null;
            Matcher m = Pattern.compile("href=\"([^\"]*?/form4\\.xml)\"", Pattern.CASE_INSENSITIVE).matcher(html);
            String bestUrl = null;
            while (m.find()) {
                String rel = m.group(1).trim();
                String full = rel.startsWith("http") ? rel : "https://www.sec.gov" + rel;
                if (!rel.toLowerCase(Locale.ROOT).contains("xslf345")) return full;
                if (bestUrl == null) bestUrl = full;
            }
            return bestUrl;
        } catch (Exception e) { return null; }
    }

    private static Map<String, List<AlertEntry>> parseForm4(String xml, long minimumUsd, Map<String, String> cikToRequestedTicker) throws Exception {
        Map<String, List<AlertEntry>> alerts = new LinkedHashMap<>();
        String payload = extractXmlPayload(xml);
        if (payload.isBlank()) return alerts;
        JsonNode root = new XmlMapper().readTree(payload);
        JsonNode issuer = root.path("issuer");
        String normalizedCik = issuer.path("issuerCik").asText(issuer.path("issuerCIK").asText("")).replaceFirst("^0+(?!$)", "");
        String ticker = cikToRequestedTicker.getOrDefault(normalizedCik, issuer.path("issuerTradingSymbol").asText("Unknown"));

        JsonNode rOwnerNode = root.path("reportingOwner");
        boolean isInsider = false;
        String ownerName = "Unknown Owner", position = "Unknown Position";
        Iterable<JsonNode> owners = rOwnerNode.isArray() ? rOwnerNode : (rOwnerNode.isObject() ? List.of(rOwnerNode) : List.of());
        for (JsonNode owner : owners) {
            if (isOfficerOrDirector(owner)) {
                isInsider = true;
                ownerName = owner.path("reportingOwnerId").path("rptOwnerName").asText("Unknown Owner");
                position = extractPosition(owner);
                break;
            }
        }
        if (!isInsider) return alerts;

        JsonNode nonDeriv = root.path("nonDerivativeTable");
        if (nonDeriv.isMissingNode()) nonDeriv = root.path("ownershipDocument").path("nonDerivativeTable");
        if (!nonDeriv.isMissingNode()) {
            JsonNode nonTrans = nonDeriv.path("nonDerivativeTransaction");
            if (!nonTrans.isMissingNode()) {
                if (nonTrans.isArray()) {
                    for (JsonNode tx : nonTrans) {
                        AlertEntry e = processTransaction(tx, ownerName, position, minimumUsd);
                        if (e != null) alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(e);
                    }
                } else if (nonTrans.isObject()) {
                    AlertEntry e = processTransaction(nonTrans, ownerName, position, minimumUsd);
                    if (e != null) alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(e);
                }
            }
        }
        alerts.putIfAbsent(ticker, new ArrayList<>());
        return alerts;
    }

    private static boolean isOfficerOrDirector(JsonNode reportingOwner) {
        JsonNode rel = reportingOwner.path("reportingOwnerRelationship");
        if (rel.isMissingNode()) return false;
        String isDir = rel.path("isDirector").asText(), isOff = rel.path("isOfficer").asText();
        return "true".equalsIgnoreCase(isDir) || "1".equals(isDir) || "true".equalsIgnoreCase(isOff) || "1".equals(isOff);
    }

    private static String extractPosition(JsonNode owner) {
        JsonNode rel = owner.path("reportingOwnerRelationship");
        List<String> titles = new ArrayList<>();
        if (!rel.isMissingNode()) {
            appendIfPresent(rel, "officerTitle", titles);
            appendIfPresent(rel, "directorTitle", titles);
            appendIfPresent(rel, "otherTitle", titles);
            if (!titles.isEmpty()) return String.join(", ", titles);
        }
        for (String path : new String[]{"relationshipTitle", "reportingOwnerId.rptOwnerTitle"}) {
            String val = pathValue(owner, path);
            if (val != null && !val.isBlank()) return val;
        }
        return "Unknown Position";
    }

    private static void appendIfPresent(JsonNode rel, String field, List<String> titles) {
        JsonNode node = rel.path(field);
        if (!node.isMissingNode() && !node.asText().isBlank()) titles.add(node.asText().trim());
    }

    private static String pathValue(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) if ((node = node.path(part)).isMissingNode()) return null;
        return node.asText(null);
    }

    private static AlertEntry processTransaction(JsonNode tx, String ownerName, String position, long minimumUsd) {
        String code = tx.path("transactionCoding").path("transactionCode").asText();
        if (!"P".equals(code) && !"S".equals(code)) return null;
        long shares = extractLong(tx, "transactionAmounts.transactionShares");
        double price = extractDouble(tx, "transactionAmounts.transactionPricePerShare");
        if (shares <= 0 || price <= 0) return null;
        double amount = shares * price;
        if (amount < minimumUsd) return null;
        String date = extractText(tx, "transactionDate", "");
        if (date.length() >= 10) date = date.substring(0, 10);
        long ownedAfter = extractLong(tx, "postTransactionAmounts.sharesOwnedFollowingTransaction");
        if (ownedAfter <= 0) ownedAfter = extractLong(tx, "sharesOwnedFollowingTransaction");
        return new AlertEntry(ownerName, position, "P".equals(code) ? "BUY" : "SELL", extractText(tx, "securityTitle", "stock"), shares, price, amount, "true".equalsIgnoreCase(tx.path("transactionCoding").path("is10b51Transaction").asText()), date, ownedAfter);
    }

    private static long extractLong(JsonNode root, String path) {
        JsonNode n = nodeAt(root, path);
        if (n.isNumber()) return n.asLong(0);
        if (n.isTextual() && !n.asText().isBlank()) return parseLongSafely(n.asText());
        JsonNode v = n.path("value");
        if (!v.isMissingNode() && !v.isNull()) {
            if (v.isNumber()) return v.asLong(0);
            if (v.isTextual() && !v.asText().isBlank()) return parseLongSafely(v.asText());
        }
        return 0;
    }

    private static double extractDouble(JsonNode root, String path) {
        JsonNode n = nodeAt(root, path);
        if (n.isNumber()) return n.asDouble(0.0);
        if (n.isTextual() && !n.asText().isBlank()) return parseDoubleSafely(n.asText());
        JsonNode v = n.path("value");
        if (!v.isMissingNode() && !v.isNull()) {
            if (v.isNumber()) return v.asDouble(0.0);
            if (v.isTextual() && !v.asText().isBlank()) return parseDoubleSafely(v.asText());
        }
        return 0.0;
    }

    private static String extractText(JsonNode root, String path, String fallback) {
        JsonNode n = nodeAt(root, path);
        if (!n.isMissingNode() && !n.asText().isBlank()) return n.asText();
        JsonNode v = n.path("value");
        return !v.isMissingNode() && !v.asText().isBlank() ? v.asText() : fallback;
    }

    private static JsonNode nodeAt(JsonNode root, String path) {
        JsonNode node = root;
        for (String p : path.split("\\.")) if ((node = node.path(p)).isMissingNode()) return node;
        return node;
    }

    private static long parseLongSafely(String t) { try { return (long) Double.parseDouble(t.replaceAll("[^0-9.\\-]", "")); } catch (Exception e) { return 0; } }
    private static double parseDoubleSafely(String t) { try { return Double.parseDouble(t.replaceAll("[^0-9.\\-]", "")); } catch (Exception e) { return 0.0; } }

    private static String extractXmlPayload(String raw) {
        if (raw == null) return "";
        String clean = "";
        int start = raw.indexOf("<XML>");
        if (start >= 0 && raw.indexOf("</XML>", start) > start) clean = raw.substring(start + 5, raw.indexOf("</XML>", start));
        if (clean.isBlank()) {
            Matcher m = Pattern.compile("<ownershipDocument[^>]*>.*?</ownershipDocument>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(raw);
            if (m.find()) clean = m.group(0);
        }
        if (clean.isBlank()) return "";
        return clean.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "").replaceAll("&(?!(amp|apos|quot|lt|gt|#\\d+);)", "&amp;").replaceAll("</\\s+", "</").replaceAll("<\\s+(?=[a-zA-Z_/?!])", "<").replaceAll("<(?=[^a-zA-Z_/?!])", "&lt;").trim();
    }

    private static boolean sendNotification(String message) {
        String dingUrl = System.getenv("DING_WEBHOOK_URL");
        if (dingUrl != null && !dingUrl.isBlank()) return sendDingTalkWebhook(dingUrl, System.getenv("DING_WEBHOOK_SIGN"), "Insider Alert", message);
        String discordUrl = System.getenv("DISCORD_WEBHOOK_URL");
        return discordUrl != null && !discordUrl.isBlank() && sendDiscordMessages(discordUrl, "Insider Alert", message);
    }

    private static void sendErrorNotification(String errMsg) {
        String dingUrl = System.getenv("DING_WEBHOOK_URL");
        if (dingUrl != null && !dingUrl.isBlank()) sendDingTalkWebhook(dingUrl, System.getenv("DING_WEBHOOK_SIGN"), "Insider Bot Error", errMsg);
        else {
            String dUrl = System.getenv("DISCORD_WEBHOOK_URL");
            if (dUrl != null && !dUrl.isBlank()) sendDiscordMessages(dUrl, "Insider Bot Error", errMsg);
        }
    }

    private static boolean sendDingTalkWebhook(String url, String secret, String title, String msg) {
        try {
            String signed = buildDingTalkUrl(url, secret);
            String payload = "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"" + escapeJson(title) + "\",\"text\":\"" + escapeJson("### " + title + "\n\n" + msg) + "\"}}";
            HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build().send(HttpRequest.newBuilder().uri(URI.create(signed)).timeout(HTTP_TIMEOUT).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            return res.statusCode() >= 200 && res.statusCode() < 300 && (res.body() == null ? "" : res.body()).replace(" ", "").contains("\"errcode\":0");
        } catch (Exception e) { return false; }
    }

    private static String buildDingTalkUrl(String url, String secret) throws Exception {
        if (secret == null || secret.isBlank()) return url;
        long ts = System.currentTimeMillis();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return url + (url.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign=" + URLEncoder.encode(Base64.getEncoder().encodeToString(mac.doFinal((ts + "\n" + secret).getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8);
    }

    private static boolean sendDiscordMessages(String url, String title, String msg) {
        try {
            String full = "**" + title + "**\n" + msg;
            if (escapeJson(full).length() <= 2000) return sendSingleDiscordMessage(url, full);
            StringBuilder chunk = new StringBuilder();
            boolean success = true;
            for (String line : full.split("\n", -1)) {
                if (escapeJson(chunk + line + "\n").length() <= 2000) chunk.append(line).append("\n");
                else {
                    if (chunk.length() > 0 && !sendSingleDiscordMessage(url, chunk.toString())) success = false;
                    chunk.setLength(0);
                    chunk.append(line).append("\n");
                }
            }
            if (chunk.length() > 0 && !sendSingleDiscordMessage(url, chunk.toString())) success = false;
            return success;
        } catch (Exception e) { return false; }
    }

    private static boolean sendSingleDiscordMessage(String url, String content) {
        try {
            HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build().send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(HTTP_TIMEOUT).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + escapeJson(content) + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
            return res.statusCode() >= 200 && res.statusCode() < 300;
        } catch (Exception e) { return false; }
    }

    private static String escapeJson(String v) { return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }

    private static class MasterIndex {
        final String indexDate, content;
        MasterIndex(String indexDate, String content) { this.indexDate = indexDate; this.content = content; }
    }
}