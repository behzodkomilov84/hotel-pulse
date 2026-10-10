package behzoddev.hotelpulse.faktura;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faktura.uz hujjati (GetDocuments javobidagi bitta element) → kerakli maydonlar.
 * Hujjat modeli API hujjatlarida yozilmagan — shuning uchun har bir maydon bir nechta nom bo'yicha
 * (katta-kichik harfga qaramay, ichma-ich "Seller.Inn" ham) qidiriladi; xom JSON baribir saqlanadi.
 */
public final class FakturaParser {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tashkent");
    private static final Pattern MS_DATE = Pattern.compile("/Date\\((-?\\d+)");
    private static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private FakturaParser() {
    }

    /**
     * @param counterpartyInn kiruvchida — sotuvchi, chiquvchida — xaridor
     * @param total           QQSsiz summa (bo'lsa)
     */
    public record Parsed(String uniqueId, Integer type, Integer status, String number, LocalDate date,
                         String counterpartyInn, String counterpartyName,
                         BigDecimal total, BigDecimal vat, BigDecimal totalWithVat) {

        /** QQSsiz summa: total, bo'lmasa (QQS bilan − QQS), bo'lmasa QQS bilan. */
        public BigDecimal net() {
            if (total != null) {
                return total;
            }
            if (totalWithVat != null && vat != null) {
                return totalWithVat.subtract(vat);
            }
            return totalWithVat;
        }
    }

    public static Parsed parse(JsonNode doc, boolean inbox) {
        // Import modeli (api.faktura.uz/help): head.sender.sender_info.{INN, company_name},
        // document.{document_number, document_date}, column_summary_values.{column_subtotal, column_vat_value, ...}.
        String unique = text(doc, "UniqueId", "uniqueId", "DocumentUniqueId", "DocumentId", "Id", "Guid");
        String side = inbox ? "Seller" : "Buyer";
        String party = inbox ? "sender" : "receiver";
        String info = "head." + party + "." + party + "_info";
        return new Parsed(unique,
                integer(doc, "DocumentType", "DocType", "Type", "document_type"),
                integer(doc, "Status", "DocumentStatus", "State", "document_status"),
                text(doc, "DocumentNumber", "document_number", "document.document_number", "Number", "InvoiceNumber",
                        "FacturaNo", "FacturaDoc.FacturaNo"),
                date(doc, "DocumentDate", "document_date", "document.document_date", "InvoiceDate", "Date",
                        "FacturaDate", "FacturaDoc.FacturaDate", "CreatedDate", "Created"),
                digits(text(doc, side + "Inn", side + "Tin", side + ".Inn", side + ".Tin", side + "Company.Inn",
                        party + "Inn", party + "Tin", party + ".Inn", party + "_info.INN", party + "." + party + "_info.INN",
                        info + ".INN", "PartnerInn", "ContractorInn")),
                text(doc, side + "Name", side + ".Name", side + "Company.Name",
                        party + "Name", party + "CompanyName", party + ".Name", party + "_info.company_name",
                        party + "." + party + "_info.company_name", info + ".company_name",
                        "PartnerName", "ContractorName"),
                decimal(doc, "TotalPrice", "Total", "TotalSum", "Subtotal", "Amount", "TotalAmount",
                        "column_summary_values.column_subtotal", "column_subtotal"),
                decimal(doc, "TotalVat", "Vat", "VatSum", "TotalVatSum",
                        "column_summary_values.column_vat_value", "column_vat_value"),
                decimal(doc, "TotalPriceWithVat", "TotalWithVat", "TotalWithTaxes", "SubtotalWithTaxes",
                        "TotalSumWithVat", "TotalDeliverySumWithVat",
                        "column_summary_values.column_subtotal_with_taxes", "column_subtotal_with_taxes"));
    }

    /** Didox hujjat turi (ГНК kodi, "002" — ЭСФ) → umumiy tur: 002 → 2, qolganlari → 1000 + kod (xarajatga kirmaydi). */
    public static final int DIDOX_TYPE_BASE = 1000;

    /**
     * Didox ro'yxat elementi. Holatlar umumiy (Faktura.uz) kodlariga o'giriladi — imzolangan = 24 va h.k.
     * Didox holatlari (SDK bo'yicha): 0 qoralama, 1 yuborilgan, 2 imzolangan, 3 rad etilgan, 4 bekor qilingan,
     * 5 xato, 6 qisman imzolangan, 7 imzo kutilmoqda, 8 ishlanmoqda, 9 muddati o'tgan. Xom JSON saqlanadi.
     */
    public static Parsed parseDidox(JsonNode doc, boolean inbox) {
        String side = inbox ? "seller" : "buyer";
        String typeCode = text(doc, "doctype", "docType", "doc_type", "type");
        Integer type = null;
        if (typeCode != null && typeCode.matches("\\d{1,4}")) {
            int t = Integer.parseInt(typeCode);
            type = t == 2 ? 2 : DIDOX_TYPE_BASE + t;
        }
        Integer raw = integer(doc, "doc_status", "docStatus", "status");
        return new Parsed(
                text(doc, "doc_id", "docId", "_id", "id", "didox_id", "uuid"),
                type,
                raw == null ? null : didoxStatus(raw),
                text(doc, "doc_number", "docNumber", "number", "name"),
                date(doc, "doc_date", "docDate", "date", "created", "createdAt", "created_at"),
                digits(text(doc, "partnerTin", "partner_tin", "partner.tin", "partnerInn", side + "Tin", side + "_tin",
                        side + ".tin", inbox ? "senderTin" : "receiverTin")),
                text(doc, "partnerCompany", "partner_company", "partnerName", "partner_name", "partner.name",
                        side + "Name", side + "_name", side + ".name", inbox ? "senderName" : "receiverName"),
                decimal(doc, "total_delivery_sum", "totalDeliverySum", "delivery_sum", "total_sum_without_vat"),
                decimal(doc, "total_vat_sum", "totalVatSum", "vat_sum", "vatSum"),
                decimal(doc, "total_sum", "totalSum", "total_delivery_sum_with_vat", "totalDeliverySumWithVat", "sum"));
    }

    static int didoxStatus(int s) {
        return switch (s) {
            case 1, 8 -> 21;
            case 2 -> 24;
            case 3 -> 23;
            case 4 -> 34;
            case 6, 7 -> 22;
            case 9 -> 27;
            default -> 20;
        };
    }

    // ------------------------------------------------------------------ maydon qidirish

    /** Birinchi topilgan (null bo'lmagan) maydon; nomlar katta-kichik harfga qaramay, "A.B" — ichma-ich. */
    public static JsonNode field(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode cur = node;
            for (String part : name.split("\\.")) {
                cur = child(cur, part);
                if (cur == null) {
                    break;
                }
            }
            if (cur != null && !cur.isNull() && !cur.isMissingNode()) {
                return cur;
            }
        }
        return null;
    }

    private static JsonNode child(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode exact = node.get(name);
        if (exact != null) {
            return exact;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    static String text(JsonNode doc, String... names) {
        JsonNode n = field(doc, names);
        if (n == null || !n.isValueNode()) {
            return null;
        }
        String s = n.asString().trim();
        return s.isEmpty() ? null : s;
    }

    static Integer integer(JsonNode doc, String... names) {
        JsonNode n = field(doc, names);
        if (n == null) {
            return null;
        }
        if (n.isNumber()) {
            return n.asInt();
        }
        try {
            return Integer.valueOf(n.asString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static BigDecimal decimal(JsonNode doc, String... names) {
        JsonNode n = field(doc, names);
        if (n == null) {
            return null;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        String s = n.asString().replaceAll("[\\s  ]", "").replace(',', '.');
        try {
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ISO ("2024-05-01", "2024-05-01T10:00:00"), "01.05.2024", epoch (ms yoki s), "/Date(1714521600000)/". */
    static LocalDate date(JsonNode doc, String... names) {
        JsonNode n = field(doc, names);
        if (n == null) {
            return null;
        }
        if (n.isNumber()) {
            return epoch(n.asLong());
        }
        return parseDate(n.asString());
    }

    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        Matcher ms = MS_DATE.matcher(s);
        if (ms.find()) {
            return epoch(Long.parseLong(ms.group(1)));
        }
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                if (s.length() > 10 && (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:\\d{2}$"))) {
                    return java.time.OffsetDateTime.parse(s).atZoneSameInstant(ZONE).toLocalDate();
                }
                return LocalDate.parse(s.substring(0, 10));
            }
            if (s.matches("\\d{2}\\.\\d{2}\\.\\d{4}.*")) {
                return LocalDate.parse(s.substring(0, 10), DOTTED);
            }
            if (s.matches("-?\\d+")) {
                return epoch(Long.parseLong(s));
            }
        } catch (DateTimeParseException | NumberFormatException ignored) {
            // noma'lum format
        }
        return null;
    }

    private static LocalDate epoch(long v) {
        // 10^11 dan katta — millisekund, aks holda sekund.
        Instant i = Math.abs(v) > 100_000_000_000L ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
        return i.atZone(ZONE).toLocalDate();
    }

    /** Faqat raqamlar (INN): "301 234 567" → "301234567"; bo'sh — null. */
    static String digits(String s) {
        if (s == null) {
            return null;
        }
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    /** Nomlarni solishtirish uchun: kichik harf, qo'shtirnoq va tashkiliy shakl (MChJ, OOO, LLC…) olib tashlangan. */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String s = name.toLowerCase(Locale.ROOT)
                .replace('ё', 'е').replace('ʻ', '\'').replace('ʼ', '\'').replace('`', '\'').replace('‘', '\'').replace('’', '\'');
        s = s.replaceAll("[\"«»“”„]", " ");
        s = s.replaceAll("(?<![\\p{L}])(mchj|mas'uliyati cheklangan jamiyat|масъулияти чекланган жамият|мчж|ооо|оао|зао|ао|ип|xk|хк|aj|аж|qk|қк|llc|ltd|jsc|inc)(?![\\p{L}])", " ");
        return s.replaceAll("[^\\p{L}\\p{N}']+", " ").trim();
    }
}
