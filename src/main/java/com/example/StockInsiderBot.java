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

    private static final Map<String, String> FALLBACK_TICKER_MAP = Map.of(
            "BRKB", "1067983",
            "BRK-B", "1067983",
            "MSFT", "0000789019",
            "ZTS", "0001555285",
            "STZ", "0001593873"
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

    // ====== 主入口 ======
    public static void main(String[] args) {
        // ... 原有 main 逻辑不变，只修改 buildGroupedNotification 方法
    }

    // ====== AlertEntry & MasterIndex ======
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

    // ====== 优化的 Markdown 构建 ======
    private static String buildGroupedNotification(Map<String,List<AlertEntry>> alertsByTicker,
                                                   String indexDate,
                                                   long minimumUsd,
                                                   int lookbackDays,
                                                   int processedCount,
                                                   int failedCount,
                                                   List<String> unmappedTickers) {

        StringBuilder msg = new StringBuilder();
        msg.append("🔔 **内部人交易警报**\n\n");
        msg.append("📅 报告日期：").append(formatDate(indexDate))
           .append("  🔎 扫描范围：最近 ").append(lookbackDays).append(" 天")
           .append("  💰 阈值：").append(formatAmount(minimumUsd))
           .append("  📄 已处理 Form 4：").append(processedCount).append("\n");
        if (failedCount > 0) {
            msg.append("⚠️ 处理失败：").append(failedCount).append("\n");
        }
        if (unmappedTickers != null && !unmappedTickers.isEmpty()) {
            msg.append("⚠️ 未映射股票：").append(String.join(", ", unmappedTickers)).append("\n");
        }

        msg.append("\n");

        for (Map.Entry<String, List<AlertEntry>> e : alertsByTicker.entrySet()) {
            String ticker = e.getKey();
            List<AlertEntry> trades = new ArrayList<>(e.getValue());
            trades.sort(Comparator.comparing((AlertEntry a)->a.transactionDate));

            long buyCount = trades.stream().filter(t->"BUY".equals(t.type)).count();
            long sellCount = trades.stream().filter(t->"SELL".equals(t.type)).count();
            double totalAmount = trades.stream().mapToDouble(t->t.amount).sum();

            // 折叠
            msg.append("<details>\n<summary>")
               .append(ticker).append(" - ").append(trades.size())
               .append(" 笔交易，总计 ").append(formatAmount(totalAmount))
               .append(" ｜ 买入 ").append(buyCount)
               .append(" ｜ 卖出 ").append(sellCount)
               .append("</summary>\n\n");

            msg.append("| 操作 | 日期 | 人员 | 职位 | 股数 | 价格 | 持股后 |\n");
            msg.append("|------|------|------|------|------|------|--------|\n");

            for (AlertEntry t : trades) {
                msg.append("| ")
                   .append("BUY".equals(t.type)?"🔴 买入":"🟢 卖出").append(" | ")
                   .append(formatDate(t.transactionDate)).append(" | ")
                   .append(safeText(t.ownerName,"Unknown")).append(" | ")
                   .append(translatePosition(t.position)).append(" | ")
                   .append(formatNumber(t.shares)).append(" | ")
                   .append("$").append(String.format("%,.2f", t.price)).append(" | ")
                   .append(t.sharesOwnedAfter>0?formatNumber(t.sharesOwnedAfter):"N/A")
                   .append(" |\n");
            }

            msg.append("</details>\n\n");
        }

        msg.append("说明：P = Purchase 买入，S = Sale 卖出；10b5-1 表示预设交易计划。");
        return msg.toString().trim();
    }

    // ====== 其它格式化方法 ======
    private static String formatDate(String dateStr) { 
        if(dateStr==null||dateStr.isBlank()) return "N/A";
        String d = dateStr.replaceAll("-",""); 
        if(d.length()==8) return d.substring(0,4)+"年"+d.substring(4,6)+"月"+d.substring(6,8)+"日"; 
        return dateStr; 
    }
    private static String formatNumber(long num) {
        if(num>=1_000_000_000) return String.format("%.2fB", num/1_000_000_000.0);
        if(num>=1_000_000) return String.format("%.2fM", num/1_000_000.0);
        if(num>=10_000) return String.format("%.1fK", num/1_000.0);
        return String.format("%,d", num);
    }
    private static String formatAmount(double amount) {
        double abs=Math.abs(amount);
        if(abs>=1_000_000_000) return String.format("$%.2fB", amount/1_000_000_000.0);
        if(abs>=1_000_000) return String.format("$%.2fM", amount/1_000_000.0);
        if(abs>=1_000) return String.format("$%.1fK", amount/1_000.0);
        return String.format("$%,.0f", amount);
    }
    private static String safeText(String value,String fallback) { return value==null||value.isBlank()?fallback:value.trim();}
    private static String translatePosition(String eng){
        if(eng==null||eng.isBlank()) return "未知职位";
        String l = eng.toLowerCase(Locale.ROOT);
        for(Map.Entry<String,String> e:POSITION_TRANSLATIONS.entrySet()){ if(l.contains(e.getKey())) return e.getValue(); }
        return eng.trim();
    }

    // ====== 其它方法保持原样，不改动 ======
}
