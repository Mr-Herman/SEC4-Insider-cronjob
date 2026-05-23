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
        String trans = POSITION_TRANSLATIONS.get(lower);
        if (trans != null) return trans;
        for (Map.Entry<String, String> entry : POSITION_TRANSLATIONS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return eng;
    }

    private static String formatDate(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.length() != 8) return yyyyMMdd;
        try {
            return yyyyMMdd.substring(0,4) + "年" + yyyyMMdd.substring(4,6) + "月" + yyyyMMdd.substring(6,8) + "日";
        } catch (Exception e) {
            return yyyyMMdd;
        }
    }

    private static String formatNumber(long num) {
        if (num >= 1_000_000) return String.format("%.1fM", num / 1_000_000.0);
        if (num >= 1_000) return String.format("%.1fK", num / 1_000.0);
        return Long.toString(num);
    }

    private static String formatAmount(double amount) {
        if (amount >= 1_000_000) return String.format("$%.1fM", amount / 1_000_000.0);
        if (amount >= 1_000) return String.format("$%.1fK", amount / 1_000.0);
        return String.format("$%.0f", amount);
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

                String actionIcon = e.type.equals("BUY") ? "📈 买入" : "📉 卖出";
                if (e.type.equals("BUY")) msg.append("🔴 ");
                msg.append("**").append(ticker).append("** · ")
                   .append(actionIcon).append(" · **")
                   .append(amountStr).append("**\n");
                msg.append("  ").append(date).append(" · ").append(e.ownerName).append("\n");
                String translatedPos = translatePosition(e.position);
                msg.append("  ").append(translatedPos);
                if (!planIcon.isEmpty()) msg.append(planIcon);
                msg.append("\n");
                msg.append("  ").append(sharesStr).append(" 股 @ **$")
                   .append(String.format("%,.2f", e.price))
                   .append("** · 持股后 ").append(positionStr).append("\n\n");
            }
        }
        return msg.toString().trim();
    }

    private static String buildMissingNotification(String[] tickers, String reason) {
        StringBuilder msg = new StringBuilder();
        msg.append("🔔 内部人交易警报\n\n");
        for (String ticker : tickers) {
            msg.append("▶ ").append(ticker).append("\n  ").append(reason).append("\n\n");
        }
        return msg.toString().trim();
    }

    // ==================== 辅助方法 ====================
    private static boolean debugEnabled = DEFAULT_DEBUG;
    private static void setDebug(boolean enabled) { debugEnabled = enabled; }
    private static void logDebug(String message) { if (debugEnabled) System.out.println("DEBUG: " + message); }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        String positional = null;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) continue;
            if (arg.startsWith("--")) {
                String normalized = arg.substring(2);
                String[] parts = normalized.split("=", 2);
                if (parts.length == 2) options.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
                else if (parts.length == 1) options.put(parts[0].toLowerCase(Locale.ROOT), "true");
            } else if (positional == null) positional = arg;
        }
        options.put("positional", positional);
        return options;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static long parseLong(String value, long fallback) {
        try { if (value != null && !value.isBlank()) return Long.parseLong(value.trim()); } catch (Exception ignored) {}
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        try { if (value != null && !value.isBlank()) return Integer.parseInt(value.trim()); } catch (Exception ignored) {}
        return fallback;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return !(trimmed.equals("false") || trimmed.equals("0") || trimmed.equals("no") || trimmed.equals("off"));
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
        } catch (Exception e) {}
        if (map.isEmpty()) map.putAll(FALLBACK_TICKER_MAP);
        return map;
    }

    private static String findCikForTicker(String ticker, Map<String, String> tickerToCik) {
        String cleanInput = ticker.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (cleanInput.isBlank()) return null;
        for (Map.Entry<String, String> e : tickerToCik.entrySet()) {
            if (e.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").equals(cleanInput)) return e.getValue();
        }
        for (Map.Entry<String, String> e : FALLBACK_TICKER_MAP.entrySet()) {
            if (e.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").equals(cleanInput)) return e.getValue();
        }
        return null;
    }

    private static MasterIndex findMasterIndex(LocalDate startDate, int maxLookbackDays) {
        LocalDate date = startDate;
        StringBuilder combined = new StringBuilder();
        LocalDate foundDate = null;
        for (int i = 0; i < maxLookbackDays; i++) {
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String url = SEC_BASE + "edgar/daily-index/" + date.getYear() + "/QTR" + ((date.getMonthValue()-1)/3+1) + "/master." + dateStr + ".idx";
            try {
                String content = downloadText(url);
                if (content != null && !content.isBlank()) {
                    logDebug("Using SEC index: " + url);
                    combined.append(content);
                    if (foundDate == null) foundDate = date;
                }
            } catch (Exception e) {}
            date = date.minusDays(1);
        }
        return combined.length() > 0 && foundDate != null ? new MasterIndex(foundDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")), combined.toString()) : null;
    }

    private static String downloadText(String url) throws Exception {
        Exception lastEx = null;
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
                        if (entity == null) throw new IllegalStateException("Empty response from " + url);
                        return EntityUtils.toString(entity);
                    } else if (status == 403 || status == 404) {
                        throw new IllegalStateException("HTTP " + status + " for " + url);
                    } else {
                        lastEx = new IllegalStateException("HTTP " + status + " for " + url + " (attempt " + attempt + ")");
                        if (attempt < 3) Thread.sleep(2000);
                    }
                }
            } catch (Exception e) {
                lastEx = e;
                if (attempt < 3) Thread.sleep(1000);
            }
        }
        throw lastEx != null ? lastEx : new IllegalStateException("Failed to download " + url + " after 3 attempts");
    }

    private static List<String> parseMasterIdx(String content, Set<String> ciks) {
        Set<String> cleanCiks = new HashSet<>();
        for (String cik : ciks) cleanCiks.add(cik.replaceFirst("^0+(?!$)", ""));
        Set<String> urls = new LinkedHashSet<>();
        if (content == null) return new ArrayList<>();
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("CIK|") || line.startsWith("-----")) continue;
            String[] parts = line.split("\\|", 6);
            if (parts.length < 5) continue;
            String fileCik = parts[0].trim().replaceFirst("^0+(?!$)", "");
            String formType = parts[2].trim();
            if (!formType.startsWith("4")) continue;
            if (cleanCiks.contains(fileCik)) {
                String filename = parts[4].trim();
                if (!filename.isEmpty()) urls.add(SEC_BASE + filename);
            }
        }
        return new ArrayList<>(urls);
    }

    private static List<String> fetchForm4UrlsFromEdgarBrowse(Set<String> ciks, int maxLookbackDays) {
        Set<String> urls = new LinkedHashSet<>();
        for (String cik : ciks) {
            try {
                String browseUrl = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=" + cik + "&type=4&owner=include&count=100&output=atom";
                String atomXml = downloadText(browseUrl);
                if (atomXml != null && !atomXml.isBlank()) urls.addAll(parseBrowseEdgarAtom(atomXml, maxLookbackDays));
            } catch (Exception e) {
                System.err.println("Warning: browse-edgar fallback failed for CIK " + cik);
            }
        }
        return new ArrayList<>(urls);
    }

    private static List<String> parseBrowseEdgarAtom(String atomXml, int maxLookbackDays) {
        Set<String> urls = new LinkedHashSet<>();
        LocalDate threshold = LocalDate.now().minusDays(maxLookbackDays);
        Pattern entryPattern = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
        Pattern datePattern = Pattern.compile("<filing-date>(.*?)</filing-date>", Pattern.DOTALL);
        Pattern hrefPattern = Pattern.compile("<filing-href>(.*?)</filing-href>", Pattern.DOTALL);
        Matcher entryMatcher = entryPattern.matcher(atomXml);
        while (entryMatcher.find()) {
            String entry = entryMatcher.group(1);
            Matcher dateMatcher = datePattern.matcher(entry);
            Matcher hrefMatcher = hrefPattern.matcher(entry);
            if (!dateMatcher.find() || !hrefMatcher.find()) continue;
            String filingDate = dateMatcher.group(1).trim();
            String filingHref = hrefMatcher.group(1).trim();
            try {
                LocalDate date = LocalDate.parse(filingDate);
                if (date.isBefore(threshold)) continue;
                String xmlUrl = findForm4XmlUrlFromIndexPage(filingHref);
                if (xmlUrl != null) urls.add(xmlUrl);
            } catch (Exception e) {}
        }
        return new ArrayList<>(urls);
    }

    private static String findForm4XmlUrlFromIndexPage(String indexUrl) {
        try {
            String html = downloadText(indexUrl);
            if (html == null || html.isBlank()) return null;
            Pattern p = Pattern.compile("href=\"([^\"]*?/form4\\.xml)\"", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            String best = null;
            while (m.find()) {
                String rel = m.group(1).trim();
                String full = rel.startsWith("http") ? rel : "https://www.sec.gov" + rel;
                if (!rel.toLowerCase(Locale.ROOT).contains("xslf345")) return full;
                if (best == null) best = full;
            }
            return best;
        } catch (Exception e) { return null; }
    }

    private static Map<String, List<AlertEntry>> parseForm4(String xml, long minimumUsd, Map<String, String> cikToRequestedTicker) throws Exception {
        Map<String, List<AlertEntry>> alerts = new LinkedHashMap<>();
        String xmlPayload = extractXmlPayload(xml);
        if (xmlPayload.isBlank()) { logDebug("Skipping file: Could not extract valid XML payload."); return alerts; }
        XmlMapper mapper = new XmlMapper();
        JsonNode root = mapper.readTree(xmlPayload);
        JsonNode issuer = root.path("issuer");
        String rawXmlCik = extractText(issuer, "issuerCik", extractText(issuer, "issuerCIK", "Unknown"));
        String normalizedXmlCik = rawXmlCik.replaceFirst("^0+(?!$)", "");
        String ticker = cikToRequestedTicker.getOrDefault(normalizedXmlCik, extractText(issuer, "issuerTradingSymbol", "Unknown"));

        JsonNode reportingOwnerNode = root.path("reportingOwner");
        JsonNode primaryOwner = reportingOwnerNode;
        if (reportingOwnerNode.isArray()) {
            boolean found = false;
            for (JsonNode node : reportingOwnerNode) {
                if (isValidReporter(node)) { primaryOwner = node; found = true; break; }
            }
            if (!found && reportingOwnerNode.size() > 0) primaryOwner = reportingOwnerNode.get(0);
        }
        if (!isValidReporter(primaryOwner)) { logDebug("Skipping Form 4 for " + ticker + " - reporter is not a valid insider/officer/director."); return alerts; }

        String ownerName = extractText(primaryOwner, "reportingOwnerId.rptOwnerName", "Unknown Owner");
        String position = extractPosition(primaryOwner);

        JsonNode nonDeriv = root.path("nonDerivativeTable");
        if (nonDeriv.isMissingNode()) nonDeriv = root.path("ownershipDocument").path("nonDerivativeTable");
        if (!nonDeriv.isMissingNode()) {
            JsonNode nonTrans = nonDeriv.path("nonDerivativeTransaction");
            if (!nonTrans.isMissingNode()) {
                if (nonTrans.isArray()) {
                    for (JsonNode tx : nonTrans) {
                        AlertEntry entry = processTransaction(tx, ownerName, position, minimumUsd);
                        if (entry != null) alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                    }
                } else if (nonTrans.isObject()) {
                    AlertEntry entry = processTransaction(nonTrans, ownerName, position, minimumUsd);
                    if (entry != null) alerts.computeIfAbsent(ticker, k -> new ArrayList<>()).add(entry);
                }
            } else { logDebug("No non-derivativeTransaction for " + ticker); }
        } else { logDebug("No non-derivativeTable for " + ticker); }
        alerts.putIfAbsent(ticker, new ArrayList<>());
        return alerts;
    }

    private static boolean isValidReporter(JsonNode node) {
        String isDir = extractText(node, "reportingOwnerRelationship.isDirector", "false");
        String isOff = extractText(node, "reportingOwnerRelationship.isOfficer", "false");
        String isTen = extractText(node, "reportingOwnerRelationship.isTenPercentOwner", "false");
        String isOth = extractText(node, "reportingOwnerRelationship.isOther", "false");
        if ("true".equalsIgnoreCase(isDir) || "1".equals(isDir) ||
            "true".equalsIgnoreCase(isOff) || "1".equals(isOff) ||
            "true".equalsIgnoreCase(isTen) || "1".equals(isTen) ||
            "true".equalsIgnoreCase(isOth) || "1".equals(isOth)) return true;
        return !extractText(node, "reportingOwnerRelationship.officerTitle", "").isBlank();
    }

    private static String extractPosition(JsonNode node) {
        List<String> titles = new ArrayList<>();
        String off = extractText(node, "reportingOwnerRelationship.officerTitle", "");
        if (!off.isBlank()) titles.add(off);
        String dir = extractText(node, "reportingOwnerRelationship.directorTitle", "");
        if (!dir.isBlank()) titles.add(dir);
        String oth = extractText(node, "reportingOwnerRelationship.otherTitle", "");
        if (!oth.isBlank()) titles.add(oth);
        if (!titles.isEmpty()) return String.join(", ", titles);
        String rel = extractText(node, "reportingOwnerRelationship.relationshipTitle", "");
        if (!rel.isBlank()) return rel;
        String rpt = extractText(node, "reportingOwnerId.rptOwnerTitle", "");
        if (!rpt.isBlank()) return rpt;
        return "Unknown Position";
    }

    private static AlertEntry processTransaction(JsonNode tx, String ownerName, String position, long minimumUsd) {
        String code = extractText(tx, "transactionCoding.transactionCode", "");
        if (!"P".equalsIgnoreCase(code) && !"S".equalsIgnoreCase(code)) {
            if (debugEnabled) logDebug("Skipping transaction: code=" + code + " (not P/S)");
            return null;
        }
        long shares = extractLong(tx, "transactionAmounts.transactionShares");
        if (shares <= 0) shares = extractLong(tx, "transactionShares");
        double price = extractDouble(tx, "transactionAmounts.transactionPricePerShare");
        if (price <= 0) price = extractDouble(tx, "transactionPricePerShare");
        if (shares <= 0 || price <= 0) {
            if (debugEnabled) logDebug("Skipping transaction: code=" + code + " shares=" + shares + " price=" + price);
            return null;
        }
        double amount = shares * price;
        if (amount < minimumUsd) {
            if (debugEnabled) logDebug("Skipping transaction: code=" + code + " amount=" + amount + " < threshold=" + minimumUsd);
            return null;
        }
        String type = "P".equalsIgnoreCase(code) ? "BUY" : "SELL";
        String security = extractText(tx, "securityTitle", "stock");
        String is10b51 = extractText(tx, "transactionCoding.is10b51Transaction", "false");
        boolean isPlan = "true".equalsIgnoreCase(is10b51) || "1".equals(is10b51);
        String transactionDate = extractText(tx, "transactionDate", "");
        if (!transactionDate.isEmpty() && transactionDate.length() >= 10) transactionDate = transactionDate.substring(0,10);
        long sharesOwnedAfter = extractLong(tx, "postTransactionAmounts.sharesOwnedFollowingTransaction");
        if (sharesOwnedAfter <= 0) sharesOwnedAfter = extractLong(tx, "sharesOwnedFollowingTransaction");
        if (debugEnabled) logDebug("Creating alert: " + ownerName + " " + type + " " + shares + " shares at " + price + " amount=" + amount + " date=" + transactionDate + " ownedAfter=" + sharesOwnedAfter);
        return new AlertEntry(ownerName, position, type, security, shares, price, amount, isPlan, transactionDate, sharesOwnedAfter);
    }

    private static String extractText(JsonNode root, String path, String fallback) {
        JsonNode node = root;
        for (String part : path.split("\\.")) node = node.path(part);
        if (node.isMissingNode() || node.isNull()) return fallback;
        if (node.isTextual()) {
            String text = node.asText();
            return text.isBlank() ? fallback : text;
        }
        if (node.isObject()) {
            JsonNode val = node.path("value");
            if (!val.isMissingNode() && !val.asText().isBlank()) return val.asText();
            JsonNode empty = node.path("");
            if (!empty.isMissingNode() && !empty.asText().isBlank()) return empty.asText();
        }
        String raw = node.asText();
        return (raw != null && !raw.isBlank()) ? raw : fallback;
    }

    private static long extractLong(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) node = node.path(part);
        if (node.isMissingNode() || node.isNull()) return 0;
        if (node.isNumber()) return node.asLong(0);
        if (node.isTextual() && !node.asText().isBlank()) return parseLongSafely(node.asText());
        if (node.isObject()) {
            JsonNode val = node.path("value");
            if (!val.isMissingNode() && !val.isNull()) {
                if (val.isNumber()) return val.asLong(0);
                if (val.isTextual() && !val.asText().isBlank()) return parseLongSafely(val.asText());
            }
            JsonNode empty = node.path("");
            if (!empty.isMissingNode() && !empty.asText().isBlank()) return parseLongSafely(empty.asText());
        }
        return 0;
    }

    private static double extractDouble(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) node = node.path(part);
        if (node.isMissingNode() || node.isNull()) return 0.0;
        if (node.isNumber()) return node.asDouble(0.0);
        if (node.isTextual() && !node.asText().isBlank()) return parseDoubleSafely(node.asText());
        if (node.isObject()) {
            JsonNode val = node.path("value");
            if (!val.isMissingNode() && !val.isNull()) {
                if (val.isNumber()) return val.asDouble(0.0);
                if (val.isTextual() && !val.asText().isBlank()) return parseDoubleSafely(val.asText());
            }
            JsonNode empty = node.path("");
            if (!empty.isMissingNode() && !empty.asText().isBlank()) return parseDoubleSafely(empty.asText());
        }
        return 0.0;
    }

    private static long parseLongSafely(String text) {
        try { return (long) Double.parseDouble(text.replaceAll("[^0-9.\\-]", "")); } catch (Exception e) { return 0; }
    }
    private static double parseDoubleSafely(String text) {
        try { return Double.parseDouble(text.replaceAll("[^0-9.\\-]", "")); } catch (Exception e) { return 0.0; }
    }

    private static String extractXmlPayload(String rawText) {
        if (rawText == null) return "";
        String clean = "";
        int xmlStart = rawText.indexOf("<XML>");
        if (xmlStart >= 0) {
            int xmlEnd = rawText.indexOf("</XML>", xmlStart);
            if (xmlEnd > xmlStart) clean = rawText.substring(xmlStart+5, xmlEnd);
        }
        if (clean.isBlank()) {
            Matcher m = Pattern.compile("<ownershipDocument[^>]*>.*?</ownershipDocument>", Pattern.DOTALL).matcher(rawText);
            if (m.find()) clean = m.group(0);
        }
        if (clean.isBlank()) return "";
        clean = clean.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        clean = clean.replaceAll("&(?!(amp|apos|quot|lt|gt|#\\d+);)", "&amp;");
        clean = clean.replaceAll("</\\s+", "</");
        clean = clean.replaceAll("<\\s+(?=[a-zA-Z_/?!])", "<");
        clean = clean.replaceAll("<(?=[^a-zA-Z_/?!])", "&lt;");
        return clean.trim();
    }

    private static boolean sendNotification(String message) {
        String dingTalkUrl = System.getenv("DING_WEBHOOK_URL");
        if (dingTalkUrl != null && !dingTalkUrl.isBlank()) {
            String dingTalkSecret = System.getenv("DING_WEBHOOK_SIGN");
            return sendDingTalkWebhook(dingTalkUrl, dingTalkSecret, "内部人交易警报", message);
        }
        String discordUrl = System.getenv("DISCORD_WEBHOOK_URL");
        if (discordUrl == null || discordUrl.isBlank()) return false;
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
        if (discordUrl != null && !discordUrl.isBlank()) sendDiscordWebhook(discordUrl, "Insider Bot Error", errorMessage);
    }

    private static boolean sendDingTalkWebhook(String webhookUrl, String secret, String title, String message) {
        try {
            String signedUrl = buildDingTalkUrl(webhookUrl, secret);
            String markdown = "### " + title + "\n\n" + message;
            String payload = "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"" + escapeJson(title) + "\",\"text\":\"" + escapeJson(markdown) + "\"}}";
            HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(signedUrl)).timeout(HTTP_TIMEOUT).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300 && body.replace(" ", "").contains("\"errcode\":0");
            if (!success) System.err.println("Warning: DingTalk notification failed. status=" + response.statusCode() + " body=" + body);
            return success;
        } catch (Exception e) {
            System.err.println("Warning: failed to send DingTalk notification: " + e.getMessage());
            return false;
        }
    }

    private static String buildDingTalkUrl(String webhookUrl, String secret) throws Exception {
        if (secret == null || secret.isBlank()) return webhookUrl;
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
        try { return sendDiscordMessages(webhookUrl, title, message); } catch (Exception e) { System.err.println("Warning: failed to send Discord notification: " + e.getMessage()); return false; }
    }
    private static boolean sendDiscordMessages(String webhookUrl, String title, String message) throws Exception {
        String fullBody = "**" + title + "**\n" + message;
        if (escapeJson(fullBody).length() <= 2000) return sendSingleDiscordMessage(webhookUrl, fullBody);
        String[] lines = fullBody.split("\n", -1);
        StringBuilder chunk = new StringBuilder();
        boolean success = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String newline = (i < lines.length-1) ? "\n" : "";
            String candidate = chunk.toString() + line + newline;
            if (escapeJson(candidate).length() <= 2000) {
                chunk.append(line).append(newline);
            } else {
                if (chunk.length() > 0) { if (!sendSingleDiscordMessage(webhookUrl, chunk.toString())) success = false; chunk.setLength(0); }
                chunk.append(line).append(newline);
            }
        }
        if (chunk.length() > 0) if (!sendSingleDiscordMessage(webhookUrl, chunk.toString())) success = false;
        return success;
    }
    private static boolean sendSingleDiscordMessage(String webhookUrl, String content) {
        try {
            String payload = "{\"content\":\"" + escapeJson(content) + "\"}";
            HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(webhookUrl)).timeout(HTTP_TIMEOUT).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) { System.err.println("Warning: failed to send single Discord message: " + e.getMessage()); return false; }
    }
    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static class AlertEntry {
        final String ownerName; final String position; final String type; final long shares; final double price; final double amount;
        final boolean is10b51; final String transactionDate; final long sharesOwnedAfter;
        AlertEntry(String ownerName, String position, String type, String security, long shares, double price, double amount, boolean is10b51, String transactionDate, long sharesOwnedAfter) {
            this.ownerName = ownerName; this.position = position; this.type = type; this.shares = shares; this.price = price; this.amount = amount;
            this.is10b51 = is10b51; this.transactionDate = transactionDate; this.sharesOwnedAfter = sharesOwnedAfter;
        }
    }

    private static class MasterIndex {
        final String indexDate; final String content;
        MasterIndex(String indexDate, String content) { this.indexDate = indexDate; this.content = content; }
    }
}
