package behzoddev.hotelpulse.faktura;

import behzoddev.hotelpulse.exely.ExelyPmsClient;
import behzoddev.hotelpulse.exely.ExelyRawStore;
import behzoddev.hotelpulse.faktura.FakturaParser.Parsed;
import behzoddev.hotelpulse.report.UsaliLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ЭСФ operatori (Didox yoki Faktura.uz): ulanish (mehmonxona bo'yicha), hujjatlarni sinxronlash (kiruvchi va chiquvchi), kiruvchilarni USALI
 * moddalariga ajratish (yetkazib beruvchi qoidasi yoki qo'lda) va chiquvchilarni bronlarga bog'lash
 * (INN/nom + summa + sana bo'yicha avtomatik yoki qo'lda).
 */
@Slf4j
@Service
public class FakturaService {

    public static final String IN = "IN";
    public static final String OUT = "OUT";
    public static final String RULE = "RULE";
    public static final String MANUAL = "MANUAL";
    public static final String AUTO = "AUTO";

    /** Imzolangan (yakuniy) hujjat — faqat shular xarajatga kiradi. */
    public static final int STATUS_SIGNED = 24;
    /** Bekor/rad etilgan/o'chirilgan — hisobga olinmaydi, bronga bog'lanmaydi. */
    public static final Set<Integer> VOID_STATUSES = Set.of(23, 27, 34, 42, 44);
    /** Xarajat sifatida hisoblanadigan turlar: hisob-faktura, akt-faktura (2020 shakllari bilan). Shartnoma, oferta,
     * oldindan to'lov fakturasi (keyin asosiy faktura keladi — ikki marta sanalmasin) va boshqalar — yo'q. */
    public static final Set<Integer> EXPENSE_TYPES = Set.of(2, 5, 25, 28, 29);

    public static final Map<Integer, String> STATUS_LABELS = Map.ofEntries(
            Map.entry(20, "Мавжуд эмас"), Map.entry(21, "Контрагент жавобини кутмоқда"),
            Map.entry(22, "Имзо керак"), Map.entry(23, "Рад этилган"), Map.entry(24, "Имзоланган"),
            Map.entry(25, "Келишувда"), Map.entry(26, "Имзо кутилмоқда"), Map.entry(27, "Ўчирилган"),
            Map.entry(28, "Учинчи томон жавоби кутилмоқда"), Map.entry(31, "Бекор қилиш сўралган"),
            Map.entry(32, "Бекор қилиш сўрови кўрилмоқда"), Map.entry(33, "Бекор қилиш рад этилган"),
            Map.entry(34, "Бекор қилинган"), Map.entry(41, "Келишилди"), Map.entry(42, "Келишилмади"),
            Map.entry(43, "Имзоланди"), Map.entry(44, "Имзоланмади"), Map.entry(45, "Рад этиш кутилмоқда"));

    public static final Map<Integer, String> TYPE_LABELS = Map.ofEntries(
            Map.entry(1, "Шартнома"), Map.entry(2, "Ҳисоб-фактура"), Map.entry(3, "Оферта"),
            Map.entry(4, "Битим ҳисоботи"), Map.entry(5, "Акт ва ҳисоб-фактура"), Map.entry(6, "Олдиндан тўлов фактураси"),
            Map.entry(8, "Материал ҳисоботи"), Map.entry(14, "Жарима акти"), Map.entry(20, "Ишончнома"),
            Map.entry(22, "Тўлов топшириқномаси"), Map.entry(23, "Маълумотнома"), Map.entry(24, "Динамик ҳужжат"),
            Map.entry(25, "Фарма фактура"), Map.entry(26, "Иш варақаси"), Map.entry(27, "Акт"),
            Map.entry(28, "Ҳисоб-фактура (2020)"), Map.entry(29, "Акт ва ҳисоб-фактура (2020)"));

    /** Bitta sinxronlashda bir yo'nalish bo'yicha eng ko'p sahifa (100 tadan). */
    static final int MAX_PAGES = 200;

    private final FakturaClient client;
    private final DidoxClient didox;
    private final FakturaAccountRepository accounts;
    private final JdbcTemplate jdbc;
    private final ExelyRawStore raw;
    private final Clock clock;
    private final boolean schedulerEnabled;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public FakturaService(FakturaClient client, DidoxClient didox, FakturaAccountRepository accounts, JdbcTemplate jdbc,
                          ExelyRawStore raw, Clock clock, @Value("${app.exely.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.client = client;
        this.didox = didox;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.raw = raw;
        this.clock = clock;
        this.schedulerEnabled = schedulerEnabled;
    }

    // ================================================================== ulanish

    @Transactional(readOnly = true)
    public Optional<FakturaAccount> account(Long hotelId) {
        return accounts.findById(hotelId);
    }

    /** Faktura.uz ulanishi (testlar va eski chaqiruvlar uchun). */
    @Transactional
    public void saveAccount(Long hotelId, String inn, String username, String password, String clientId, String clientSecret) {
        saveAccount(hotelId, FakturaAccount.FAKTURA, inn, username, password, clientId, clientSecret);
    }

    /**
     * Operator ulanishini saqlaydi. Didox — faqat INN va parol; Faktura.uz — login, parol, client id/secret.
     * Bo'sh parol / client secret — saqlangani qoladi (formada ko'rsatilmaydi); operator o'zgarsa — qayta kiritiladi.
     */
    @Transactional
    public void saveAccount(Long hotelId, String provider, String inn, String username, String password,
                            String clientId, String clientSecret) {
        String companyInn = FakturaParser.digits(inn);
        if (companyInn == null || companyInn.length() != 9) {
            throw new IllegalArgumentException("ИНН 9 та рақамдан иборат бўлсин");
        }
        boolean didox = FakturaAccount.DIDOX.equals(provider);
        if (!didox && (blank(username) || blank(clientId))) {
            throw new IllegalArgumentException("Логин ва Client ID киритилсин");
        }
        FakturaAccount a = accounts.findById(hotelId).orElseGet(() -> {
            FakturaAccount n = new FakturaAccount();
            n.setHotelId(hotelId);
            return n;
        });
        String newProvider = didox ? FakturaAccount.DIDOX : FakturaAccount.FAKTURA;
        if (!newProvider.equals(a.getProvider())) {
            a.setPassword(null);
            a.setClientSecret(null);
        }
        String user = didox ? null : username.trim();
        String client = didox ? null : clientId.trim();
        boolean changed = !newProvider.equals(a.getProvider()) || !companyInn.equals(a.getCompanyInn())
                || !java.util.Objects.equals(user, a.getUsername()) || !java.util.Objects.equals(client, a.getClientId())
                || !blank(password) || !blank(clientSecret);
        a.setProvider(newProvider);
        a.setCompanyInn(companyInn);
        a.setUsername(user);
        a.setClientId(client);
        if (!blank(password)) {
            a.setPassword(password);
        }
        if (didox) {
            a.setClientSecret(null);
        } else if (!blank(clientSecret)) {
            a.setClientSecret(clientSecret.trim());
        }
        if (a.getPassword() == null) {
            throw new IllegalArgumentException(didox ? "Didox пароли киритилсин" : "Парол киритилсин");
        }
        if (!didox && a.getClientSecret() == null) {
            throw new IllegalArgumentException("Client Secret киритилсин");
        }
        if (changed) {
            a.setAccessToken(null);
            a.setTokenExpires(null);
        }
        a.setUpdatedAt(LocalDateTime.now(clock));
        accounts.save(a);
    }

    /** Ulanishni o'chiradi; olingan hujjatlar va qoidalar qoladi. */
    @Transactional
    public void disconnect(Long hotelId) {
        accounts.deleteById(hotelId);
    }

    /** Token olib, bittadan kiruvchi va chiquvchi hujjatni so'raydi. */
    public String test(Long hotelId) {
        FakturaAccount a = complete(hotelId);
        String token = freshToken(a, true);
        FakturaClient.Page in = page(a, token, true, 0, 1);
        FakturaClient.Page out = page(a, token, false, 0, 1);
        return a.getProviderName() + " билан уланиш муваффақиятли ✓"
                + (in.totalCount() != null ? " — кирувчи: " + in.totalCount() : "")
                + (out.totalCount() != null ? ", чиқувчи: " + out.totalCount() : "");
    }

    public boolean isRunning(Long hotelId) {
        return running.contains(hotelId);
    }

    /** Fonda sinxronlash; allaqachon ishlayotgan bo'lsa — false. */
    public boolean startAsync(Long hotelId) {
        if (!running.add(hotelId)) {
            return false;
        }
        CompletableFuture.runAsync(() -> {
            try {
                syncNow(hotelId);
            } finally {
                running.remove(hotelId);
            }
        });
        return true;
    }

    /** Har kuni 03:40 — barcha ulangan mehmonxonalar. */
    @Scheduled(cron = "${app.faktura.sync-cron:0 40 3 * * *}", zone = "${app.zone:Asia/Tashkent}")
    public void scheduled() {
        if (!schedulerEnabled) {
            return;
        }
        for (FakturaAccount a : accounts.findAll()) {
            if (a.isComplete() && running.add(a.getHotelId())) {
                try {
                    syncNow(a.getHotelId());
                } finally {
                    running.remove(a.getHotelId());
                }
            }
        }
    }

    /** Sinxronlash; natija ulanish holatiga yoziladi (xatoni tashlamaydi). */
    public String syncNow(Long hotelId) {
        String message;
        boolean ok;
        try {
            message = sync(hotelId);
            ok = true;
        } catch (FakturaException | IllegalArgumentException e) {
            message = e.getMessage();
            ok = false;
        } catch (RuntimeException e) {
            log.warn("ЭСФ sinxronlash ({}) xatosi", hotelId, e);
            message = "Кутилмаган хато: " + e.getClass().getSimpleName();
            ok = false;
        }
        saveStatus(hotelId, ok, message);
        return message;
    }

    String sync(Long hotelId) {
        FakturaAccount a = complete(hotelId);
        String token = freshToken(a, false);
        LocalDateTime now = LocalDateTime.now(clock);
        int[] in = fetch(hotelId, a, token, true, now);
        int[] out = fetch(hotelId, a, token, false, now);
        int ruled = applyRules(hotelId);
        int matched = matchOutgoing(hotelId);
        StringBuilder sb = new StringBuilder("Кирувчи: ").append(in[0]).append(" та, чиқувчи: ").append(out[0]).append(" та");
        if (ruled > 0) {
            sb.append("; ").append(ruled).append(" таси қоида бўйича харажат моддасига ажратилди");
        }
        if (matched > 0) {
            sb.append("; ").append(matched).append(" таси бронга боғланди");
        }
        if (in[1] + out[1] > 0) {
            sb.append("; ").append(in[1] + out[1]).append(" таси танилмади (идентификатори йўқ)");
        }
        log.info("ЭСФ sinxronlash (mehmonxona {}): {}", hotelId, sb);
        return sb.toString();
    }

    /** @return {saqlangan, tanilmagan} */
    private int[] fetch(Long hotelId, FakturaAccount a, String token, boolean inbox, LocalDateTime now) {
        int saved = 0, unknown = 0;
        int size = a.isDidox() ? DidoxClient.PAGE : FakturaClient.PAGE;
        for (int page = 0; page < MAX_PAGES; page++) {
            FakturaClient.Page p = page(a, token, inbox, page, size);
            for (JsonNode doc : p.documents()) {
                Parsed d = a.isDidox() ? FakturaParser.parseDidox(doc, inbox) : FakturaParser.parse(doc, inbox);
                String id = idOf(d);
                if (id == null) {
                    unknown++;
                    continue;
                }
                String key = a.isDidox() ? "dx:" + id : id;
                upsert(hotelId, key.length() <= 64 ? key : sha(key), inbox ? IN : OUT, d, doc.toString(), now);
                saved++;
            }
            long seen = (long) (page + 1) * size;
            if (p.documents().size() < size || (p.totalCount() != null && seen >= p.totalCount())) {
                break;
            }
        }
        return new int[]{saved, unknown};
    }

    /** Operator bo'yicha bitta sahifa; pageIndex — 0 dan. */
    private FakturaClient.Page page(FakturaAccount a, String token, boolean inbox, int pageIndex, int size) {
        return a.isDidox()
                ? didox.documents(token, inbox, pageIndex + 1, size)
                : client.documents(token, a.getCompanyInn(), inbox, pageIndex * size, size);
    }

    /** UniqueId; bo'lmasa — raqam + sana + kontragent INN'idan barqaror kalit. */
    static String idOf(Parsed d) {
        if (d.uniqueId() != null) {
            return d.uniqueId().length() <= 64 ? d.uniqueId() : sha(d.uniqueId());
        }
        if (d.number() == null || d.date() == null) {
            return null;
        }
        return sha(d.number() + "|" + d.date() + "|" + d.counterpartyInn());
    }

    private static String sha(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return "h:" + HexFormat.of().formatHex(h, 0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void upsert(Long hotelId, String id, String direction, Parsed d, String json, LocalDateTime now) {
        jdbc.update("""
                insert into faktura_documents (hotel_id, unique_id, direction, doc_type, status, number, doc_date,
                    counterparty_inn, counterparty_name, total, vat, total_with_vat, payload, fetched_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on duplicate key update doc_type = values(doc_type), status = values(status), number = values(number),
                    doc_date = values(doc_date), counterparty_inn = values(counterparty_inn),
                    counterparty_name = values(counterparty_name), total = values(total), vat = values(vat),
                    total_with_vat = values(total_with_vat), payload = values(payload), fetched_at = values(fetched_at)
                """, hotelId, id, direction, d.type(), d.status(), cut(d.number(), 100),
                d.date() == null ? null : Date.valueOf(d.date()), cut(d.counterpartyInn(), 20), cut(d.counterpartyName(), 255),
                d.total(), d.vat(), d.totalWithVat(), json, Timestamp.valueOf(now));
    }

    private String freshToken(FakturaAccount a, boolean force) {
        if (a.isDidox()) {
            // Didox tokeni muddati noma'lum — har sinxronlashda qayta kiriladi (saqlanmaydi).
            return didox.login(a.getCompanyInn(), a.getPassword());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!force && a.getAccessToken() != null && a.getTokenExpires() != null && a.getTokenExpires().isAfter(now.plusHours(1))) {
            return a.getAccessToken();
        }
        FakturaClient.Token t = client.token(a.getUsername(), a.getPassword(), a.getClientId(), a.getClientSecret());
        a.setAccessToken(t.accessToken());
        a.setTokenExpires(now.plusSeconds(t.expiresIn() > 0 ? t.expiresIn() : 86_400));
        accounts.save(a);
        return t.accessToken();
    }

    private FakturaAccount complete(Long hotelId) {
        FakturaAccount a = accounts.findById(hotelId)
                .orElseThrow(() -> new FakturaException("ЭСФ оператори уланмаган — аввал маълумотларни киритиб сақланг"));
        if (!a.isComplete()) {
            throw new FakturaException("ЭСФ оператори маълумотлари тўлиқ эмас");
        }
        return a;
    }

    private void saveStatus(Long hotelId, boolean ok, String message) {
        jdbc.update("update faktura_accounts set synced_at = ?, sync_ok = ?, sync_message = ? where hotel_id = ?",
                Timestamp.valueOf(LocalDateTime.now(clock)), ok, cut(message, 500), hotelId);
    }

    // ================================================================== kiruvchi: USALI moddalari

    /** Qoidalar bo'yicha (qo'lda belgilanganlariga tegmaydi). @return o'zgargan hujjatlar soni */
    @Transactional
    public int applyRules(Long hotelId) {
        return jdbc.update("""
                update faktura_documents d join faktura_rules r on r.hotel_id = d.hotel_id and r.seller_inn = d.counterparty_inn
                set d.usali_line = r.usali_line, d.line_source = 'RULE'
                where d.hotel_id = ? and d.direction = 'IN' and (d.line_source is null or d.line_source = 'RULE')
                  and (d.usali_line is null or d.usali_line <> r.usali_line)
                """, hotelId);
    }

    /**
     * Kiruvchi hujjat moddasini qo'lda belgilaydi (null — olib tashlash). remember — shu yetkazib beruvchining
     * barcha (qo'lda belgilanmagan) hujjatlari uchun qoida.
     */
    @Transactional
    public void assignLine(Long hotelId, long docId, UsaliLine line, boolean remember) {
        String inn = jdbc.queryForList("select counterparty_inn from faktura_documents where id = ? and hotel_id = ? and direction = 'IN'",
                String.class, docId, hotelId).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        int n = jdbc.update("update faktura_documents set usali_line = ?, line_source = ? where id = ? and hotel_id = ? and direction = 'IN'",
                line == null ? null : line.name(), line == null ? null : MANUAL, docId, hotelId);
        if (n == 0) {
            throw new IllegalArgumentException("Ҳужжат топилмади");
        }
        if (remember && inn != null) {
            if (line == null) {
                jdbc.update("delete from faktura_rules where hotel_id = ? and seller_inn = ?", hotelId, inn);
                jdbc.update("update faktura_documents set usali_line = null, line_source = null where hotel_id = ? and direction = 'IN' and counterparty_inn = ? and line_source = 'RULE'",
                        hotelId, inn);
            } else {
                jdbc.update("""
                        insert into faktura_rules (hotel_id, seller_inn, usali_line, created_at) values (?, ?, ?, ?)
                        on duplicate key update usali_line = values(usali_line)
                        """, hotelId, inn, line.name(), Timestamp.valueOf(LocalDateTime.now(clock)));
                applyRules(hotelId);
            }
        }
    }

    /** Yetkazib beruvchi INN → modda. */
    @Transactional(readOnly = true)
    public Map<String, UsaliLine> rules(Long hotelId) {
        Map<String, UsaliLine> result = new HashMap<>();
        jdbc.query("select seller_inn, usali_line from faktura_rules where hotel_id = ?", rs -> {
            UsaliLine l = line(rs.getString(2));
            if (l != null) {
                result.put(rs.getString(1), l);
            }
        }, hotelId);
        return result;
    }

    /** USALI uchun: [from, to] oylaridagi imzolangan kiruvchi hisob-fakturalar (QQSsiz), modda bo'yicha. */
    @Transactional(readOnly = true)
    public Map<UsaliLine, BigDecimal> expenses(Long hotelId, YearMonth from, YearMonth to) {
        Map<UsaliLine, BigDecimal> result = new EnumMap<>(UsaliLine.class);
        jdbc.query("select usali_line, sum(" + NET + ") from faktura_documents where " + EXPENSE_WHERE + " group by usali_line",
                rs -> {
                    UsaliLine l = line(rs.getString(1));
                    if (l != null && rs.getBigDecimal(2) != null) {
                        result.put(l, rs.getBigDecimal(2));
                    }
                }, hotelId, Date.valueOf(from.atDay(1)), Date.valueOf(to.atEndOfMonth()));
        return result;
    }

    /** [from, to] ichida Faktura.uz xarajati bor oylar. */
    @Transactional(readOnly = true)
    public Set<YearMonth> expenseMonths(Long hotelId, YearMonth from, YearMonth to) {
        Set<YearMonth> result = new TreeSet<>();
        jdbc.query("select distinct year(doc_date), month(doc_date) from faktura_documents where " + EXPENSE_WHERE,
                rs -> {
                    result.add(YearMonth.of(rs.getInt(1), rs.getInt(2)));
                }, hotelId, Date.valueOf(from.atDay(1)), Date.valueOf(to.atEndOfMonth()));
        return result;
    }

    /** Moddaga ajratilmagan imzolangan kiruvchi hisob-fakturalar (shu oylarda): soni va summasi. */
    @Transactional(readOnly = true)
    public Unassigned unassigned(Long hotelId, YearMonth from, YearMonth to) {
        return jdbc.query("select count(*), coalesce(sum(" + NET + "), 0) from faktura_documents where "
                        + BASE_WHERE + " and usali_line is null",
                rs -> {
                    rs.next();
                    return new Unassigned(rs.getInt(1), rs.getBigDecimal(2));
                }, hotelId, Date.valueOf(from.atDay(1)), Date.valueOf(to.atEndOfMonth()));
    }

    public record Unassigned(int count, BigDecimal amount) {
    }

    private static final String NET = "coalesce(total, total_with_vat - coalesce(vat, 0), total_with_vat, 0)";
    private static final String BASE_WHERE = "hotel_id = ? and direction = 'IN' and status = " + STATUS_SIGNED
            + " and doc_type in (" + String.join(",", EXPENSE_TYPES.stream().map(String::valueOf).toList()) + ")"
            + " and doc_date >= ? and doc_date <= ?";
    private static final String EXPENSE_WHERE = BASE_WHERE + " and usali_line is not null";

    // ================================================================== hujjatlar ro'yxati

    public record Doc(long id, String direction, Integer type, Integer status, String number, LocalDate date,
                      String counterpartyInn, String counterpartyName, BigDecimal total, BigDecimal vat,
                      BigDecimal totalWithVat, UsaliLine line, String lineSource, String bookingNumber, String matchSource) {

        public String statusLabel() {
            return status == null ? "—" : STATUS_LABELS.getOrDefault(status, String.valueOf(status));
        }

        public String typeLabel() {
            if (type == null) {
                return "—";
            }
            if (type >= FakturaParser.DIDOX_TYPE_BASE) {
                return "Ҳужжат (тури " + String.format("%03d", type - FakturaParser.DIDOX_TYPE_BASE) + ")";
            }
            return TYPE_LABELS.getOrDefault(type, String.valueOf(type));
        }

        public boolean signed() {
            return status != null && status == STATUS_SIGNED;
        }

        public boolean isVoid() {
            return status != null && VOID_STATUSES.contains(status);
        }

        /** Xarajatga kiradimi (modda belgilansa). */
        public boolean expense() {
            return signed() && type != null && EXPENSE_TYPES.contains(type);
        }

        public BigDecimal net() {
            if (total != null) {
                return total;
            }
            if (totalWithVat != null) {
                return vat == null ? totalWithVat : totalWithVat.subtract(vat);
            }
            return null;
        }

        public BigDecimal gross() {
            return totalWithVat != null ? totalWithVat : total;
        }
    }

    /** Yo'nalish bo'yicha hujjatlar (sana kamayishi), ixtiyoriy oy va qidiruv bilan. */
    @Transactional(readOnly = true)
    public List<Doc> documents(Long hotelId, String direction, YearMonth month, String query, boolean onlyUnassigned) {
        StringBuilder sql = new StringBuilder("select " + DOC_COLUMNS + " from faktura_documents where hotel_id = ? and direction = ?");
        List<Object> args = new ArrayList<>(List.of(hotelId, direction));
        if (month != null) {
            sql.append(" and doc_date >= ? and doc_date <= ?");
            args.add(Date.valueOf(month.atDay(1)));
            args.add(Date.valueOf(month.atEndOfMonth()));
        }
        if (query != null && !query.isBlank()) {
            sql.append(" and (counterparty_name like ? or counterparty_inn like ? or number like ? or booking_number like ?)");
            String q = "%" + query.trim() + "%";
            args.addAll(List.of(q, q, q, q));
        }
        if (onlyUnassigned) {
            sql.append(IN.equals(direction) ? " and usali_line is null" : " and booking_number is null");
        }
        sql.append(" order by doc_date desc, id desc limit 2000");
        return jdbc.query(sql.toString(), (rs, i) -> doc(rs), args.toArray());
    }

    private static final String DOC_COLUMNS = "id, direction, doc_type, status, number, doc_date, counterparty_inn, counterparty_name,"
            + " total, vat, total_with_vat, usali_line, line_source, booking_number, match_source";

    private static Doc doc(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date d = rs.getDate("doc_date");
        return new Doc(rs.getLong("id"), rs.getString("direction"), (Integer) rs.getObject("doc_type", Integer.class),
                rs.getObject("status", Integer.class), rs.getString("number"), d == null ? null : d.toLocalDate(),
                rs.getString("counterparty_inn"), rs.getString("counterparty_name"), rs.getBigDecimal("total"),
                rs.getBigDecimal("vat"), rs.getBigDecimal("total_with_vat"), line(rs.getString("usali_line")),
                rs.getString("line_source"), rs.getString("booking_number"), rs.getString("match_source"));
    }

    /** Chiquvchi rasmiy hisob-fakturalar: bron raqami → hujjatlar (bekor qilinganlarisiz). */
    @Transactional(readOnly = true)
    public Map<String, List<Doc>> outgoingByBooking(Long hotelId) {
        Map<String, List<Doc>> result = new HashMap<>();
        jdbc.query("select " + DOC_COLUMNS + " from faktura_documents where hotel_id = ? and direction = 'OUT'"
                        + " and booking_number is not null order by doc_date",
                rs -> {
                    Doc d = doc(rs);
                    if (!d.isVoid()) {
                        result.computeIfAbsent(d.bookingNumber(), k -> new ArrayList<>()).add(d);
                    }
                }, hotelId);
        return result;
    }

    /** Ulangan (yoki hujjati bor) mehmonxonami. */
    @Transactional(readOnly = true)
    public boolean enabled(Long hotelId) {
        return accounts.existsById(hotelId);
    }

    // ================================================================== chiquvchi: bronga bog'lash

    /** Qo'lda bog'lash (bo'sh — bog'lanishni olib tashlash; avtomatik qayta bog'lanmaydi). */
    @Transactional
    public void linkBooking(Long hotelId, long docId, String bookingNumber) {
        String number = blank(bookingNumber) ? null : bookingNumber.trim();
        if (number != null) {
            Integer n = jdbc.queryForObject("select count(*) from bookings where hotel_id = ? and (external_id = ? or external_id like ?)",
                    Integer.class, hotelId, "pms:" + number, "pms:" + number + "#%");
            if (n == null || n == 0) {
                throw new IllegalArgumentException("Бундай брон топилмади: " + number);
            }
        }
        int n = jdbc.update("update faktura_documents set booking_number = ?, match_source = ? where id = ? and hotel_id = ? and direction = 'OUT'",
                number, MANUAL, docId, hotelId);
        if (n == 0) {
            throw new IllegalArgumentException("Ҳужжат топилмади");
        }
    }

    /** Bron — moslash uchun. @param payers to'lovchilar (normallashtirilgan nomlar) */
    record Candidate(String number, Set<String> payers, LocalDate arrival, LocalDate departure,
                     BigDecimal accountTotal, BigDecimal bookingTotal) {
    }

    /** Avtomatik bog'lash (qo'lda bog'langanlariga tegmaydi). @return yangi bog'langanlar soni */
    @Transactional
    public int matchOutgoing(Long hotelId) {
        List<Doc> docs = jdbc.query("select " + DOC_COLUMNS + " from faktura_documents where hotel_id = ? and direction = 'OUT'"
                + " and (match_source is null or match_source = 'AUTO')", (rs, i) -> doc(rs), hotelId);
        if (docs.isEmpty()) {
            return 0;
        }
        Map<String, Candidate> candidates = candidates(hotelId);
        Map<String, String> customers = customerNames(hotelId);
        int matched = 0;
        for (Doc d : docs) {
            String number = d.isVoid() ? null : match(d.counterpartyInn(), d.counterpartyName(), d.date(), d.gross(), candidates, customers);
            if (!java.util.Objects.equals(number, d.bookingNumber())) {
                jdbc.update("update faktura_documents set booking_number = ?, match_source = ? where id = ?",
                        number, number == null ? null : AUTO, d.id());
                if (number != null) {
                    matched++;
                }
            }
        }
        return matched;
    }

    /**
     * Mos bron: to'lovchi nomi (Faktura'dagi xaridor nomi yoki uning INN'i bo'yicha Exely to'lovchisi) mos,
     * summa (Exely hisobi yoki bron jami) ±0,5% (kamida 1000), sana — kelishdan 45 kun oldin … ketishdan 90 kun keyin.
     * Bir nechta bo'lsa — ketish sanasi hujjat sanasiga eng yaqini (teng bo'lsa — bog'lanmaydi).
     */
    static String match(String inn, String name, LocalDate date, BigDecimal amount,
                        Map<String, Candidate> candidates, Map<String, String> customerNameByInn) {
        if (date == null || amount == null || amount.signum() <= 0) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        String n1 = FakturaParser.normalizeName(name);
        if (!n1.isEmpty()) {
            names.add(n1);
        }
        if (inn != null && customerNameByInn.containsKey(inn)) {
            String n2 = FakturaParser.normalizeName(customerNameByInn.get(inn));
            if (!n2.isEmpty()) {
                names.add(n2);
            }
        }
        if (names.isEmpty()) {
            return null;
        }
        String best = null;
        long bestGap = Long.MAX_VALUE;
        boolean tie = false;
        for (Candidate c : candidates.values()) {
            if (!sameName(names, c.payers()) || !sameAmount(amount, c.accountTotal(), c.bookingTotal())) {
                continue;
            }
            if (date.isBefore(c.arrival().minusDays(45)) || date.isAfter(c.departure().plusDays(90))) {
                continue;
            }
            long gap = Math.abs(ChronoUnit.DAYS.between(c.departure(), date));
            if (gap < bestGap) {
                best = c.number();
                bestGap = gap;
                tie = false;
            } else if (gap == bestGap) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    static boolean sameName(Set<String> names, Set<String> payers) {
        for (String a : names) {
            for (String b : payers) {
                if (a.equals(b)) {
                    return true;
                }
                String shorter = a.length() <= b.length() ? a : b;
                String longer = shorter == a ? b : a;
                if (shorter.length() >= 6 && longer.contains(shorter)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean sameAmount(BigDecimal amount, BigDecimal... targets) {
        for (BigDecimal t : targets) {
            if (t == null || t.signum() <= 0) {
                continue;
            }
            BigDecimal tolerance = t.multiply(new BigDecimal("0.005")).max(new BigDecimal("1000"));
            if (amount.subtract(t).abs().compareTo(tolerance) <= 0) {
                return true;
            }
        }
        return false;
    }

    /** Exely PMS bronlari: to'lovchilar (Exely hisoblaridan), sanalar va summalar. */
    private Map<String, Candidate> candidates(Long hotelId) {
        Map<String, LocalDate[]> dates = new HashMap<>();
        Map<String, BigDecimal> totals = new HashMap<>();
        jdbc.query("select external_id, arrival_date, departure_date, total_amount from bookings"
                        + " where hotel_id = ? and origin = 'EXELY_PMS' and status not in ('CANCELLED', 'NO_SHOW')",
                rs -> {
                    String id = rs.getString(1);
                    String number = id.startsWith("pms:") ? id.substring(4) : id;
                    int hash = number.lastIndexOf('#');
                    if (hash > 0) {
                        number = number.substring(0, hash);
                    }
                    LocalDate a = rs.getDate(2).toLocalDate(), b = rs.getDate(3).toLocalDate();
                    dates.merge(number, new LocalDate[]{a, b}, (x, y) -> new LocalDate[]{
                            x[0].isBefore(y[0]) ? x[0] : y[0], x[1].isAfter(y[1]) ? x[1] : y[1]});
                    totals.merge(number, rs.getBigDecimal(4), BigDecimal::add);
                }, hotelId);
        Map<String, Candidate> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.payloads(hotelId, ExelyRawStore.INVOICES).entrySet()) {
            LocalDate[] d = dates.get(e.getKey());
            if (d == null) {
                continue;
            }
            Set<String> payers = new LinkedHashSet<>();
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode inv : ExelyPmsClient.tree(e.getValue())) {
                JsonNode payer = inv.path("payer").get("name");
                if (payer != null && payer.isValueNode()) {
                    String p = FakturaParser.normalizeName(payer.asString());
                    if (!p.isEmpty()) {
                        payers.add(p);
                    }
                }
                for (JsonNode item : inv.path("items")) {
                    JsonNode t = item.get("total");
                    if (t != null && t.isNumber()) {
                        total = total.add(t.decimalValue());
                    }
                }
            }
            if (!payers.isEmpty()) {
                result.put(e.getKey(), new Candidate(e.getKey(), payers, d[0], d[1], total, totals.get(e.getKey())));
            }
        }
        return result;
    }

    /** Exely to'lovchilari: INN → nom. */
    private Map<String, String> customerNames(Long hotelId) {
        Map<String, String> result = new HashMap<>();
        for (String json : raw.payloads(hotelId, ExelyRawStore.CUSTOMER).values()) {
            JsonNode c = ExelyPmsClient.tree(json);
            String inn = FakturaParser.digits(FakturaParser.text(c, "inn"));
            String name = FakturaParser.text(c, "name");
            if (inn != null && name != null) {
                result.put(inn, name);
            }
        }
        return result;
    }

    // ================================================================== yordamchilar

    static UsaliLine line(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UsaliLine.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
