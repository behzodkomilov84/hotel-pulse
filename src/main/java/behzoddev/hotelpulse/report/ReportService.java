package behzoddev.hotelpulse.report;

import behzoddev.hotelpulse.controller.Formats;
import behzoddev.hotelpulse.entity.BookingStatus;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.DebtReport;
import behzoddev.hotelpulse.kpi.HotelKpi;
import behzoddev.hotelpulse.kpi.Period;
import behzoddev.hotelpulse.kpi.StayMetrics;
import behzoddev.hotelpulse.report.ReportData.PaymentRow;
import behzoddev.hotelpulse.report.ReportData.ServiceRow;
import behzoddev.hotelpulse.report.ReportData.Stay;
import behzoddev.hotelpulse.service.DebtService;
import behzoddev.hotelpulse.service.KpiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static behzoddev.hotelpulse.report.ReportTable.cell;

/**
 * Jadval hisobotlarini quradi. Asosiy ko'rsatkichlar (bandlik, ADR, daromad) mehmonxona sahifasidagi bilan bir xil
 * manbadan — KpiService; ro'yxatlar va guruhlashlar — ReportData (SQL).
 * Guruh bo'yicha daromad (manba, xona turi) — bron narxi davrga tushgan kechalarga bo'lingan holda.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    static final DateTimeFormatter DAY_SHORT = DateTimeFormatter.ofPattern("dd.MM");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    static final String[] WEEKDAYS = {"Du", "Se", "Ch", "Pa", "Ju", "Sh", "Ya"};
    static final String[] MONTHS = {"Yanvar", "Fevral", "Mart", "Aprel", "May", "Iyun", "Iyul", "Avgust", "Sentyabr",
            "Oktyabr", "Noyabr", "Dekabr"};

    private final ReportData data;
    private final KpiService kpiService;
    private final DebtService debtService;
    private final UsaliExpenseService expenses;
    private final Formats fmt;

    /** Jadval hisobot; noma'lum yoki jadval bo'lmagan kalit — null. */
    @Transactional(readOnly = true)
    public ReportTable build(String key, Hotel hotel, Period p) {
        String cur = fmt.currencyLabel(hotel.getCurrency());
        return switch (key) {
            case "arrivals" -> stayList(hotel, data.arrivingIn(hotel.getId(), p).stream()
                    .filter(s -> s.status() != BookingStatus.CANCELLED).toList(), cur, "Kelish");
            case "departures" -> stayList(hotel, data.departingIn(hotel.getId(), p).stream()
                    .filter(Stay::active).sorted(Comparator.comparing(Stay::departure)).toList(), cur, "Ketish");
            case "inhouse" -> inHouse(hotel, p, cur);
            case "guests" -> guests(hotel, p, cur);
            case "meals" -> meals(hotel, p, cur);
            case "daily" -> daily(hotel, p, cur);
            case "manager-period" -> managerPeriod(hotel, p, cur);
            case "flash" -> flash(hotel, p, cur);
            case "room-types" -> roomTypes(hotel, p, cur);
            case "history-forecast" -> historyForecast(hotel, cur);
            case "demand-calendar" -> demandCalendar(hotel);
            case "demand-intensity" -> demandIntensity(hotel);
            case "financial" -> financial(hotel, p, cur);
            case "sources" -> sources(hotel, p, cur);
            case "sources-detail" -> sourcesDetail(hotel, p, cur);
            case "clients" -> clients(hotel, p, cur);
            case "cancellations" -> cancellations(hotel, p, cur);
            case "cancel-window" -> cancelWindow(hotel, p);
            case "agents" -> agents(hotel, p, cur);
            case "managers" -> managers(hotel, p, cur);
            case "payments" -> payments(hotel, p, cur);
            case "payment-methods" -> paymentMethods(hotel, p, cur);
            case "services-summary" -> servicesSummary(hotel, p, cur);
            case "services-detail" -> servicesDetail(hotel, p, cur);
            case "balances" -> balances(hotel, cur);
            case "usali-summary" -> usaliSummary(hotel, p, cur);
            case "usali-rooms" -> usaliRooms(hotel, p, cur);
            case "usali-fb" -> usaliFb(hotel, p, cur);
            case "usali-other" -> usaliOther(hotel, p, cur);
            case "usali-stats" -> usaliStats(hotel, p, cur);
            default -> null;
        };
    }

    // ================================================================ Kunlik operatsiya

    private ReportTable stayList(Hotel hotel, List<Stay> stays, String cur, String dateLabel) {
        Map<String, String> types = data.roomTypes(hotel.getId());
        Map<String, String> rooms = data.rooms(hotel.getId());
        ReportTable t = new ReportTable().col("Kelish").col("Ketish").num("Kecha").col("Bron").col("Mehmon")
                .col("Xona turi").col("Xona").num("Mehmonlar").col("Manba").col("Holat")
                .num("Summa, " + cur).num("Qoldiq, " + cur);
        int guests = 0;
        BigDecimal total = BigDecimal.ZERO, balance = BigDecimal.ZERO;
        for (Stay s : stays) {
            t.row(s.arrival().format(DAY), s.departure().format(DAY), s.nights(), s.number(), nz(s.guest()),
                    nz(types.get(s.roomTypeId())), nz(rooms.get(s.roomId())), s.guests(), nz(s.source()), status(s.status()),
                    fmt.amount(s.total()), debtCell(s.balance()));
            guests += s.guests();
            total = total.add(s.total());
            balance = balance.add(s.balance());
        }
        t.total("Jami", "", "", stays.size() + " ta", "", "", "", guests, "", "", fmt.amount(total), fmt.amount(balance));
        return t;
    }

    private ReportTable inHouse(Hotel hotel, Period p, String cur) {
        LocalDate d = clampToday(p);
        Map<String, String> types = data.roomTypes(hotel.getId());
        Map<String, String> rooms = data.rooms(hotel.getId());
        List<Stay> list = data.overlapping(hotel.getId(), new Period("custom", d, d)).stream()
                .filter(s -> s.inHouseOn(d))
                .sorted(Comparator.comparing((Stay s) -> nz(rooms.get(s.roomId())), roomOrder()))
                .toList();
        ReportTable t = new ReportTable().col("Xona").col("Xona turi").col("Bron").col("Mehmon").num("Mehmonlar")
                .col("Kelish").col("Ketish").col("Manba").num("Summa, " + cur).num("Qoldiq, " + cur);
        int guests = 0;
        BigDecimal balance = BigDecimal.ZERO;
        for (Stay s : list) {
            t.row(nz(rooms.get(s.roomId())), nz(types.get(s.roomTypeId())), s.number(), nz(s.guest()), s.guests(),
                    s.arrival().format(DAY), s.departure().format(DAY), nz(s.source()), fmt.amount(s.total()), debtCell(s.balance()));
            guests += s.guests();
            balance = balance.add(s.balance());
        }
        t.total("Jami", "", list.size() + " ta yashash", "", guests, "", "", "", "", fmt.amount(balance));
        t.note("Sana: " + d.format(DAY) + " · band xonalar: " + list.size() + " / " + hotel.getRoomsCount()
                + " (" + fmt.pct(ratio(list.size(), hotel.getRoomsCount())) + ")");
        return t;
    }

    private ReportTable guests(Hotel hotel, Period p, String cur) {
        record Acc(int[] stays, long[] nights, BigDecimal[] sum, LocalDate[] last, java.util.Set<String> sources) {
        }
        Map<String, Acc> by = new LinkedHashMap<>();
        List<Stay> stays = data.overlapping(hotel.getId(), p);
        Function<Stay, BigDecimal> rev = roomRevenueOf(hotel, p, stays);
        for (Stay s : stays) {
            if (!s.active()) {
                continue;
            }
            String name = s.guest() == null || s.guest().isBlank() ? "—" : s.guest().strip();
            Acc a = by.computeIfAbsent(name, k -> new Acc(new int[1], new long[1], new BigDecimal[]{BigDecimal.ZERO},
                    new LocalDate[1], new java.util.TreeSet<>()));
            a.stays()[0]++;
            a.nights()[0] += s.roomNightsIn(p);
            a.sum()[0] = a.sum()[0].add(rev.apply(s));
            if (a.last()[0] == null || s.departure().isAfter(a.last()[0])) {
                a.last()[0] = s.departure();
            }
            if (s.source() != null) {
                a.sources().add(s.source());
            }
        }
        ReportTable t = new ReportTable().col("Mehmon / to'lovchi").num("Yashashlar").num("Kechalar (davrda)")
                .num("Daromad (davrda), " + cur).col("Oxirgi ketish").col("Manbalar");
        by.entrySet().stream().sorted(Comparator.comparing((Map.Entry<String, Acc> e) -> e.getValue().sum()[0]).reversed())
                .forEach(e -> {
                    Acc a = e.getValue();
                    t.row(e.getKey(), a.stays()[0], a.nights()[0], fmt.amount(a.sum()[0]), a.last()[0].format(DAY),
                            String.join(", ", a.sources()));
                });
        t.total("Jami: " + by.size() + " ta mehmon", by.values().stream().mapToInt(a -> a.stays()[0]).sum(),
                by.values().stream().mapToLong(a -> a.nights()[0]).sum(),
                fmt.amount(by.values().stream().map(a -> a.sum()[0]).reduce(BigDecimal.ZERO, BigDecimal::add)), "", "");
        t.note("Shaxsiy ma'lumotlar (telefon, hujjat) ko'rsatilmaydi — faqat ism va tashriflar.");
        return t;
    }

    private ReportTable meals(Hotel hotel, Period p, String cur) {
        List<Stay> stays = data.overlapping(hotel.getId(), new Period("custom", p.from().minusDays(1), p.to()))
                .stream().filter(Stay::active).toList();
        Map<LocalDate, BigDecimal> meals = new TreeMap<>();
        Map<LocalDate, Integer> mealLines = new TreeMap<>();
        for (ServiceRow r : data.extraServices(hotel.getId(), p)) {
            if (r.meals()) {
                meals.merge(r.date(), r.amount(), BigDecimal::add);
                mealLines.merge(r.date(), 1, Integer::sum);
            }
        }
        ReportTable t = new ReportTable().col("Sana").col("Kun").num("Ertalab mehmonlar").num("Kelayotgan mehmonlar")
                .num("Sotilgan ovqatlanish (qator)").num("Ovqatlanish summasi, " + cur);
        int tg = 0, ta = 0, tl = 0;
        BigDecimal ts = BigDecimal.ZERO;
        for (LocalDate d = p.from(); !d.isAfter(p.to()); d = d.plusDays(1)) {
            LocalDate day = d;
            // Nonushta — kechasi qolgan mehmonlar (kecha kelgan, bugun yoki keyin ketadi).
            int morning = stays.stream().filter(s -> s.arrival().isBefore(day) && !s.departure().isBefore(day))
                    .mapToInt(Stay::guests).sum();
            int arriving = stays.stream().filter(s -> s.arrival().equals(day)).mapToInt(Stay::guests).sum();
            int lines = mealLines.getOrDefault(d, 0);
            BigDecimal sum = meals.getOrDefault(d, BigDecimal.ZERO);
            t.row(d.format(DAY), weekday(d), morning, arriving, lines, fmt.amount(sum));
            tg += morning;
            ta += arriving;
            tl += lines;
            ts = ts.add(sum);
        }
        t.total("Jami", "", tg, ta, tl, fmt.amount(ts));
        t.note("Ertalab mehmonlar — oldingi kechani mehmonxonada o'tkazganlar (nonushta rejasi uchun).");
        return t;
    }

    // ================================================================ Daromad va bandlik

    private ReportTable daily(Hotel hotel, Period p, String cur) {
        StayMetrics s = kpiService.metrics(hotel, p);
        int rooms = hotel.getRoomsCount();
        ReportTable t = new ReportTable().col("Sana").col("Kun").num("Sotilgan xonalar").num("Bandlik")
                .num("ADR, " + cur).num("RevPAR, " + cur).num("Yashash, " + cur).num("Xizmatlar, " + cur).num("Jami, " + cur);
        for (StayMetrics.DailyPoint d : s.daily()) {
            t.row(d.date().format(DAY), weekday(d.date()), d.roomsSold(), heat(d.occupancy()),
                    fmt.amount(div(d.roomRevenue(), d.roomsSold())), fmt.amount(div(d.roomRevenue(), rooms)),
                    fmt.amount(d.roomRevenue()), fmt.amount(d.revenue().subtract(d.roomRevenue())), fmt.amount(d.revenue()));
        }
        t.total("Jami", "", s.soldRoomNights(), fmt.pct(s.occupancy()), fmt.amount(s.adr()), fmt.amount(s.revpar()),
                fmt.amount(s.roomRevenue()), fmt.amount(s.extrasRevenue()), fmt.amount(s.totalRevenue()));
        return t;
    }

    private ReportTable managerPeriod(Hotel hotel, Period p, String cur) {
        HotelKpi now = kpiService.report(hotel, p);
        HotelKpi prev = kpiService.report(hotel, p.previous());
        ReportTable t = new ReportTable().col("Ko'rsatkich").num(p.label()).num(p.previous().label()).num("O'zgarish");
        metricsRows(t, now, prev, hotel, cur);
        return t;
    }

    private void metricsRows(ReportTable t, HotelKpi a, HotelKpi b, Hotel hotel, String cur) {
        StayMetrics s = a.stays(), q = b.stays();
        t.rowOf("section", "Bandlik");
        t.row("Mavjud xona-kechalar", s.availableRoomNights(), q.availableRoomNights(), "");
        t.row("Sotilgan xona-kechalar", s.soldRoomNights(), q.soldRoomNights(), change(s.soldRoomNights(), q.soldRoomNights()));
        t.row("Bandlik", fmt.pct(s.occupancy()), fmt.pct(q.occupancy()), fmt.pointChange(s.occupancy(), q.occupancy()));
        t.row("O'rtacha yashash (kecha)", fmt.number(s.avgLengthOfStay()), fmt.number(q.avgLengthOfStay()), "");
        t.rowOf("section", "Narx va daromad, " + cur);
        t.row("ADR", fmt.amount(s.adr()), fmt.amount(q.adr()), nz(fmt.change(s.adr(), q.adr())));
        t.row("RevPAR", fmt.amount(s.revpar()), fmt.amount(q.revpar()), nz(fmt.change(s.revpar(), q.revpar())));
        t.row("TRevPAR (jami daromad / mavjud xona)", fmt.amount(div(s.totalRevenue(), s.availableRoomNights())),
                fmt.amount(div(q.totalRevenue(), q.availableRoomNights())), "");
        t.row("Yashash daromadi", fmt.amount(s.roomRevenue()), fmt.amount(q.roomRevenue()), nz(fmt.change(s.roomRevenue(), q.roomRevenue())));
        t.row("Ovqatlanish", fmt.amount(s.mealsRevenue()), fmt.amount(q.mealsRevenue()), nz(fmt.change(s.mealsRevenue(), q.mealsRevenue())));
        BigDecimal so = s.extrasRevenue().subtract(s.mealsRevenue()), qo = q.extrasRevenue().subtract(q.mealsRevenue());
        t.row("Boshqa xizmatlar", fmt.amount(so), fmt.amount(qo), nz(fmt.change(so, qo)));
        t.rowOf("subtotal", "Jami daromad", fmt.amount(s.totalRevenue()), fmt.amount(q.totalRevenue()),
                nz(fmt.change(s.totalRevenue(), q.totalRevenue())));
        t.row("Tushgan to'lovlar", fmt.amount(a.paymentsReceived()), fmt.amount(b.paymentsReceived()),
                nz(fmt.change(a.paymentsReceived(), b.paymentsReceived())));
        t.rowOf("section", "Bronlar");
        t.row("Yangi bronlar", a.newBookings(), b.newBookings(), change(a.newBookings(), b.newBookings()));
        t.row("Bekor qilingan", a.cancellations(), b.cancellations(), change(a.cancellations(), b.cancellations()));
        t.row("Kelmagan (no-show)", s.noShows(), q.noShows(), "");
    }

    private ReportTable flash(Hotel hotel, Period p, String cur) {
        LocalDate d = clampToday(p);
        Period day = new Period("custom", d, d);
        Period mtd = new Period("custom", d.withDayOfMonth(1), d);
        Period ytd = new Period("custom", d.withDayOfYear(1), d);
        HotelKpi kd = kpiService.report(hotel, day), km = kpiService.report(hotel, mtd), ky = kpiService.report(hotel, ytd);
        ReportTable t = new ReportTable().col("Ko'rsatkich").num(d.format(DAY)).num("Oy boshidan").num("Yil boshidan");
        for (Object[] r : List.<Object[]>of(
                new Object[]{"Bandlik", (Function<HotelKpi, String>) k -> fmt.pct(k.stays().occupancy())},
                new Object[]{"Sotilgan xona-kechalar", (Function<HotelKpi, String>) k -> String.valueOf(k.stays().soldRoomNights())},
                new Object[]{"ADR, " + cur, (Function<HotelKpi, String>) k -> fmt.amount(k.stays().adr())},
                new Object[]{"RevPAR, " + cur, (Function<HotelKpi, String>) k -> fmt.amount(k.stays().revpar())},
                new Object[]{"Yashash daromadi, " + cur, (Function<HotelKpi, String>) k -> fmt.amount(k.stays().roomRevenue())},
                new Object[]{"Ovqatlanish, " + cur, (Function<HotelKpi, String>) k -> fmt.amount(k.stays().mealsRevenue())},
                new Object[]{"Boshqa xizmatlar, " + cur, (Function<HotelKpi, String>) k -> fmt.amount(k.stays().extrasRevenue().subtract(k.stays().mealsRevenue()))},
                new Object[]{"Jami daromad, " + cur, (Function<HotelKpi, String>) k -> fmt.amount(k.stays().totalRevenue())},
                new Object[]{"Tushgan to'lovlar, " + cur, (Function<HotelKpi, String>) k -> fmt.amount(k.paymentsReceived())},
                new Object[]{"Yangi bronlar", (Function<HotelKpi, String>) k -> String.valueOf(k.newBookings())},
                new Object[]{"Bekor qilingan", (Function<HotelKpi, String>) k -> String.valueOf(k.cancellations())},
                new Object[]{"Kelmagan (no-show)", (Function<HotelKpi, String>) k -> String.valueOf(k.stays().noShows())})) {
            @SuppressWarnings("unchecked")
            Function<HotelKpi, String> f = (Function<HotelKpi, String>) r[1];
            t.row(r[0], f.apply(kd), f.apply(km), f.apply(ky));
        }
        List<Stay> around = data.overlapping(hotel.getId(), new Period("custom", d.minusDays(1), d.plusDays(1)));
        t.row("Keladiganlar (kun)", around.stream().filter(s -> s.active() && s.arrival().equals(d)).count(), "", "");
        t.row("Ketadiganlar (kun)", around.stream().filter(s -> s.active() && s.departure().equals(d)).count(), "", "");
        t.note("Sana — davr oxiri (bugundan keyin bo'lsa — bugun).");
        return t;
    }

    private ReportTable roomTypes(Hotel hotel, Period p, String cur) {
        Map<String, String> names = data.roomTypes(hotel.getId());
        Map<String, Integer> perType = data.roomsPerType(hotel.getId());
        Map<String, long[]> nights = new LinkedHashMap<>();
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        List<Stay> stays = data.overlapping(hotel.getId(), p);
        Function<Stay, BigDecimal> rev = roomRevenueOf(hotel, p, stays);
        for (Stay s : stays) {
            if (!s.active()) {
                continue;
            }
            String type = s.roomTypeId() == null ? "?" : s.roomTypeId();
            nights.computeIfAbsent(type, k -> new long[1])[0] += s.roomNightsIn(p);
            revenue.merge(type, rev.apply(s), BigDecimal::add);
        }
        BigDecimal total = revenue.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        ReportTable t = new ReportTable().col("Xona turi").num("Xonalar").num("Sotilgan kechalar").num("Bandlik")
                .num("ADR, " + cur).num("RevPAR, " + cur).num("Daromad, " + cur).num("Ulushi");
        long totalNights = 0;
        int totalRooms = 0;
        for (String type : revenue.keySet().stream().sorted(Comparator.comparing(revenue::get).reversed()).toList()) {
            long n = nights.get(type)[0];
            int rooms = perType.getOrDefault(type, 0);
            BigDecimal r = revenue.get(type);
            t.row(names.getOrDefault(type, type.equals("?") ? "Noma'lum" : "Tur " + type), rooms == 0 ? "—" : rooms, n,
                    rooms == 0 ? "—" : fmt.pct(ratio(n, (long) rooms * p.days())), fmt.amount(div(r, n)),
                    rooms == 0 ? "—" : fmt.amount(div(r, (long) rooms * p.days())), fmt.amount(r), fmt.pct(ratio(r, total)));
            totalNights += n;
            totalRooms += rooms;
        }
        t.total("Jami", totalRooms, totalNights, totalRooms == 0 ? "" : fmt.pct(ratio(totalNights, (long) totalRooms * p.days())),
                fmt.amount(div(total, totalNights)), "", fmt.amount(total), "100%");
        t.note("Exely API bronning tarif rejasini bermaydi — shuning uchun daromad xona turlari bo'yicha. "
                + "Daromad — yashash daromadi, bronlar ulushi bo'yicha taqsimlangan (asosiy ko'rsatkichlar bilan bir xil).");
        return t;
    }

    private ReportTable historyForecast(Hotel hotel, String cur) {
        LocalDate today = kpiService.today();
        YearMonth start = YearMonth.from(today).minusMonths(6);
        ReportTable t = new ReportTable().col("Oy").col("Holat").num("Sotilgan kechalar").num("Bandlik")
                .num("ADR, " + cur).num("RevPAR, " + cur).num("Jami daromad, " + cur).num("O'tgan yil daromadi, " + cur);
        for (int i = 0; i < 13; i++) {
            YearMonth m = start.plusMonths(i);
            Period mp = new Period("custom", m.atDay(1), m.atEndOfMonth());
            StayMetrics s = kpiService.metrics(hotel, mp);
            YearMonth ly = m.minusYears(1);
            StayMetrics l = kpiService.metrics(hotel, new Period("custom", ly.atDay(1), ly.atEndOfMonth()));
            String state = m.isBefore(YearMonth.from(today)) ? "haqiqiy" : m.equals(YearMonth.from(today)) ? "joriy" : "bron qilingan";
            t.row(cell(MONTHS[m.getMonthValue() - 1] + " " + m.getYear(), m.equals(YearMonth.from(today)) ? "strong" : null),
                    state, s.soldRoomNights(), heat(s.occupancy()), fmt.amount(s.adr()), fmt.amount(s.revpar()),
                    fmt.amount(s.totalRevenue()), l.totalRevenue().signum() == 0 ? "—" : fmt.amount(l.totalRevenue()));
        }
        t.note("Kelgusi oylar — bugungacha bron qilingani (on the books). O'tgan yil — Exely'da ma'lumot bo'lsa.");
        return t;
    }

    private ReportTable demandCalendar(Hotel hotel) {
        LocalDate today = kpiService.today();
        Period p = new Period("custom", today, today.plusDays(59));
        StayMetrics s = kpiService.metrics(hotel, p);
        int rooms = hotel.getRoomsCount();
        ReportTable t = new ReportTable().col("Sana").col("Kun").num("Sotilgan").num("Bo'sh").num("Bandlik");
        for (StayMetrics.DailyPoint d : s.daily()) {
            t.row(cell(d.date().format(DAY), weekend(d.date()) ? "weekend" : null), weekday(d.date()), d.roomsSold(),
                    Math.max(0, rooms - d.roomsSold()), heat(d.occupancy()));
        }
        t.total("60 kun", "", s.soldRoomNights(), Math.max(0, s.availableRoomNights() - s.soldRoomNights()), fmt.pct(s.occupancy()));
        t.note("Bugundan boshlab 60 kun — hozirgacha bron qilingan xonalar. Rang — bandlik darajasi.");
        return t;
    }

    private ReportTable demandIntensity(Hotel hotel) {
        LocalDate today = kpiService.today();
        Period p = new Period("custom", today, today.plusDays(29));
        StayMetrics s = kpiService.metrics(hotel, p);
        Map<LocalDate, Integer> d1 = data.snapshot(hotel.getId(), today.minusDays(1));
        Map<LocalDate, Integer> d7 = data.snapshot(hotel.getId(), today.minusDays(7));
        ReportTable t = new ReportTable().col("Sana").col("Kun").num("Hozir sotilgan").num("Bo'sh")
                .num("1 kunda o'zgarish").num("7 kunda o'zgarish");
        int sum1 = 0, sum7 = 0;
        for (StayMetrics.DailyPoint d : s.daily()) {
            Integer a = d1.get(d.date()), b = d7.get(d.date());
            t.row(d.date().format(DAY), weekday(d.date()), d.roomsSold(), Math.max(0, hotel.getRoomsCount() - d.roomsSold()),
                    pickup(a == null ? null : d.roomsSold() - a), pickup(b == null ? null : d.roomsSold() - b));
            sum1 += a == null ? 0 : d.roomsSold() - a;
            sum7 += b == null ? 0 : d.roomsSold() - b;
        }
        t.total("30 kun", "", s.soldRoomNights(), "", pickup(d1.isEmpty() ? null : sum1), pickup(d7.isEmpty() ? null : sum7));
        t.note("Har kuni kelgusi kunlar holati saqlanadi (birinchi surat — shu funksiya yoqilgan kundan). "
                + "\"+\" — shu davrda yangi sotilgan xonalar, \"−\" — bekor qilingan. \"—\" — hali surat yo'q.");
        return t;
    }

    private ReportTable financial(Hotel hotel, Period p, String cur) {
        StayMetrics s = kpiService.metrics(hotel, p);
        Map<LocalDate, BigDecimal> paid = new TreeMap<>();
        for (PaymentRow r : data.payments(hotel.getId(), p)) {
            paid.merge(r.paidAt().toLocalDate(), r.amount(), BigDecimal::add);
        }
        ReportTable t = new ReportTable().col("Sana").num("Yashash, " + cur).num("Ovqatlanish, " + cur)
                .num("Boshqa xizmatlar, " + cur).num("Jami daromad, " + cur).num("Tushgan to'lovlar, " + cur);
        BigDecimal tp = BigDecimal.ZERO;
        for (StayMetrics.DailyPoint d : s.daily()) {
            BigDecimal pd = paid.getOrDefault(d.date(), BigDecimal.ZERO);
            tp = tp.add(pd);
            t.row(d.date().format(DAY), fmt.amount(d.roomRevenue()), fmt.amount(d.mealsRevenue()), fmt.amount(d.otherRevenue()),
                    fmt.amount(d.revenue()), fmt.amount(pd));
        }
        t.total("Jami", fmt.amount(s.roomRevenue()), fmt.amount(s.mealsRevenue()),
                fmt.amount(s.extrasRevenue().subtract(s.mealsRevenue())), fmt.amount(s.totalRevenue()), fmt.amount(tp));
        Period prev = p.previous();
        StayMetrics q = kpiService.metrics(hotel, prev);
        BigDecimal qp = data.payments(hotel.getId(), prev).stream().map(PaymentRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        t.rowOf("subtotal", "Oldingi davr (" + prev.label() + ")", fmt.amount(q.roomRevenue()), fmt.amount(q.mealsRevenue()),
                fmt.amount(q.extrasRevenue().subtract(q.mealsRevenue())), fmt.amount(q.totalRevenue()), fmt.amount(qp));
        t.rowOf("subtotal", "O'zgarish", nz(fmt.change(s.roomRevenue(), q.roomRevenue())), nz(fmt.change(s.mealsRevenue(), q.mealsRevenue())),
                nz(fmt.change(s.extrasRevenue().subtract(s.mealsRevenue()), q.extrasRevenue().subtract(q.mealsRevenue()))),
                nz(fmt.change(s.totalRevenue(), q.totalRevenue())), nz(fmt.change(tp, qp)));
        return t;
    }

    // ================================================================ Manbalar va mijozlar

    private ReportTable sources(Hotel hotel, Period p, String cur) {
        Map<String, long[]> stat = new LinkedHashMap<>();   // [yashashlar, kechalar, bekor]
        Map<String, BigDecimal> rev = new LinkedHashMap<>();
        List<Stay> stays = data.overlapping(hotel.getId(), p);
        Function<Stay, BigDecimal> roomRev = roomRevenueOf(hotel, p, stays);
        for (Stay s : stays) {
            String src = src(s);
            long[] a = stat.computeIfAbsent(src, k -> new long[3]);
            if (s.active()) {
                a[0]++;
                a[1] += s.roomNightsIn(p);
                rev.merge(src, roomRev.apply(s), BigDecimal::add);
            }
        }
        for (Stay s : data.arrivingIn(hotel.getId(), p)) {
            if (s.status() == BookingStatus.CANCELLED) {
                stat.computeIfAbsent(src(s), k -> new long[3])[2]++;
            }
        }
        BigDecimal total = rev.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        ReportTable t = new ReportTable().col("Manba").num("Yashashlar").num("Kechalar").num("Daromad, " + cur)
                .num("Ulushi").num("ADR, " + cur).num("Bekor qilingan").num("Bekor %");
        long ts = 0, tn = 0, tc = 0;
        for (String src : stat.keySet().stream().sorted(Comparator.comparing((String k) -> rev.getOrDefault(k, BigDecimal.ZERO)).reversed()).toList()) {
            long[] a = stat.get(src);
            BigDecimal r = rev.getOrDefault(src, BigDecimal.ZERO);
            t.row(src, a[0], a[1], fmt.amount(r), fmt.pct(ratio(r, total)), fmt.amount(div(r, a[1])), a[2],
                    fmt.pct(ratio(a[2], a[0] + a[2])));
            ts += a[0];
            tn += a[1];
            tc += a[2];
        }
        t.total("Jami", ts, tn, fmt.amount(total), "100%", fmt.amount(div(total, tn)), tc, fmt.pct(ratio(tc, ts + tc)));
        t.note("Daromad — yashash daromadi, bronlar ulushi bo'yicha taqsimlangan. Bekor qilingan — kelish sanasi shu davrda bo'lganlari.");
        return t;
    }

    private ReportTable sourcesDetail(Hotel hotel, Period p, String cur) {
        List<Stay> stays = data.overlapping(hotel.getId(), p);
        Function<Stay, BigDecimal> rev = roomRevenueOf(hotel, p, stays);
        Map<String, List<Stay>> by = stays.stream().filter(Stay::active)
                .collect(Collectors.groupingBy(this::src, TreeMap::new, Collectors.toList()));
        ReportTable t = new ReportTable().col("Manba / bron").col("Mehmon").col("Kelish").col("Ketish")
                .num("Kechalar (davrda)").num("Daromad (davrda), " + cur).col("Holat");
        BigDecimal total = BigDecimal.ZERO;
        long nights = 0;
        for (Map.Entry<String, List<Stay>> e : by.entrySet()) {
            BigDecimal sr = e.getValue().stream().map(s -> rev.apply(s)).reduce(BigDecimal.ZERO, BigDecimal::add);
            long sn = e.getValue().stream().mapToLong(s -> s.roomNightsIn(p)).sum();
            t.rowOf("section", e.getKey() + " — " + e.getValue().size() + " ta", "", "", "", sn, fmt.amount(sr), "");
            for (Stay s : e.getValue().stream().sorted(Comparator.comparing(Stay::arrival)).toList()) {
                t.row(s.number(), nz(s.guest()), s.arrival().format(DAY), s.departure().format(DAY), s.roomNightsIn(p),
                        fmt.amount(rev.apply(s)), status(s.status()));
            }
            total = total.add(sr);
            nights += sn;
        }
        t.total("Jami", "", "", "", nights, fmt.amount(total), "");
        return t;
    }

    private ReportTable clients(Hotel hotel, Period p, String cur) {
        Map<String, BigDecimal[]> by = new LinkedHashMap<>();   // [daromad, qarz]
        Map<String, Integer> count = new LinkedHashMap<>();
        Map<String, String> source = new LinkedHashMap<>();
        List<Stay> stays = data.overlapping(hotel.getId(), p);
        Function<Stay, BigDecimal> rev = roomRevenueOf(hotel, p, stays);
        for (Stay s : stays) {
            if (!s.active()) {
                continue;
            }
            String name = s.guest() == null || s.guest().isBlank() ? "—" : s.guest().strip();
            BigDecimal[] a = by.computeIfAbsent(name, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = a[0].add(rev.apply(s));
            a[1] = a[1].add(s.balance());
            count.merge(name, 1, Integer::sum);
            source.putIfAbsent(name, src(s));
        }
        ReportTable t = new ReportTable().col("Mijoz").col("Asosiy manba").num("Yashashlar")
                .num("Daromad (davrda), " + cur).num("Qarz (hozir), " + cur);
        List<String> top = by.keySet().stream().sorted(Comparator.comparing((String k) -> by.get(k)[0]).reversed()).limit(100).toList();
        for (String name : top) {
            t.row(name, source.get(name), count.get(name), fmt.amount(by.get(name)[0]), debtCell(by.get(name)[1]));
        }
        t.note("Eng ko'p daromad keltirgan 100 ta mijoz (bronni rasmiylashtirgan shaxs yoki kompaniya).");
        return t;
    }

    private ReportTable cancellations(Hotel hotel, Period p, String cur) {
        Map<String, long[]> stat = new TreeMap<>();      // [jami, bekor, no-show]
        Map<String, BigDecimal> lost = new TreeMap<>();
        for (Stay s : data.arrivingIn(hotel.getId(), p)) {
            long[] a = stat.computeIfAbsent(src(s), k -> new long[3]);
            a[0]++;
            if (s.status() == BookingStatus.CANCELLED) {
                a[1]++;
                lost.merge(src(s), s.total(), BigDecimal::add);
            } else if (s.status() == BookingStatus.NO_SHOW) {
                a[2]++;
            }
        }
        ReportTable t = new ReportTable().col("Manba").num("Yashashlar (kelishi davrda)").num("Bekor qilingan")
                .num("Bekor %").num("Kelmagan").num("Yo'qotilgan summa, " + cur);
        long ta = 0, tc = 0, tn = 0;
        BigDecimal tl = BigDecimal.ZERO;
        for (String src : stat.keySet().stream().sorted(Comparator.comparing((String k) -> stat.get(k)[1]).reversed()).toList()) {
            long[] a = stat.get(src);
            BigDecimal l = lost.getOrDefault(src, BigDecimal.ZERO);
            t.row(src, a[0], a[1], fmt.pct(ratio(a[1], a[0])), a[2], fmt.amount(l));
            ta += a[0];
            tc += a[1];
            tn += a[2];
            tl = tl.add(l);
        }
        t.total("Jami", ta, tc, fmt.pct(ratio(tc, ta)), tn, fmt.amount(tl));
        return t;
    }

    private ReportTable cancelWindow(Hotel hotel, Period p) {
        String[] buckets = {"O'sha kuni", "1–3 kun", "4–7 kun", "8–14 kun", "15–30 kun", "31+ kun"};
        Map<String, long[]> by = new TreeMap<>();
        long[] total = new long[buckets.length];
        long counted = 0, leadSum = 0;
        for (Stay s : data.arrivingIn(hotel.getId(), p)) {
            if (s.status() != BookingStatus.CANCELLED || s.cancelledAt() == null) {
                continue;
            }
            long lead = Math.max(0, ChronoUnit.DAYS.between(s.cancelledAt().toLocalDate(), s.arrival()));
            int b = lead == 0 ? 0 : lead <= 3 ? 1 : lead <= 7 ? 2 : lead <= 14 ? 3 : lead <= 30 ? 4 : 5;
            by.computeIfAbsent(src(s), k -> new long[buckets.length])[b]++;
            total[b]++;
            counted++;
            leadSum += lead;
        }
        ReportTable t = new ReportTable().col("Manba");
        for (String b : buckets) {
            t.num(b);
        }
        t.num("Jami");
        for (Map.Entry<String, long[]> e : by.entrySet()) {
            Object[] row = new Object[buckets.length + 2];
            row[0] = e.getKey();
            long sum = 0;
            for (int i = 0; i < buckets.length; i++) {
                row[i + 1] = e.getValue()[i];
                sum += e.getValue()[i];
            }
            row[buckets.length + 1] = sum;
            t.row(row);
        }
        Object[] tot = new Object[buckets.length + 2];
        tot[0] = "Jami";
        for (int i = 0; i < buckets.length; i++) {
            tot[i + 1] = total[i] + " (" + fmt.pct(ratio(total[i], counted)) + ")";
        }
        tot[buckets.length + 1] = counted;
        t.total(tot);
        t.note("Kelish sanasi davrda bo'lgan bekor qilingan yashashlar: kelishdan necha kun oldin bekor qilingan. "
                + "O'rtacha: " + (counted == 0 ? "—" : fmt.number((double) leadSum / counted) + " kun") + ". "
                + "Bekor qilish vaqti — Exely'da bron oxirgi o'zgargan vaqt.");
        return t;
    }

    private ReportTable agents(Hotel hotel, Period p, String cur) {
        Map<String, long[]> stat = new TreeMap<>();
        Map<String, BigDecimal[]> sums = new TreeMap<>();   // [narx, komissiya]
        for (Stay s : data.arrivingIn(hotel.getId(), p)) {
            if (!s.active()) {
                continue;
            }
            String src = src(s);
            stat.computeIfAbsent(src, k -> new long[2]);
            stat.get(src)[0]++;
            stat.get(src)[1] += s.nights();
            BigDecimal[] a = sums.computeIfAbsent(src, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = a[0].add(s.total());
            if (s.commission() != null) {
                a[1] = a[1].add(s.commission());
            }
        }
        ReportTable t = new ReportTable().col("Agent / kanal").num("Yashashlar").num("Kechalar")
                .num("Narx, " + cur).num("Komissiya, " + cur).num("Komissiya %").num("Sof daromad, " + cur);
        BigDecimal tr = BigDecimal.ZERO, tc = BigDecimal.ZERO;
        long ts = 0, tn = 0;
        for (String src : sums.keySet().stream().sorted(Comparator.comparing((String k) -> sums.get(k)[1]).reversed()
                .thenComparing(k -> sums.get(k)[0], Comparator.reverseOrder())).toList()) {
            BigDecimal[] a = sums.get(src);
            t.row(src, stat.get(src)[0], stat.get(src)[1], fmt.amount(a[0]), fmt.amount(a[1]), fmt.pct(ratio(a[1], a[0])),
                    fmt.amount(a[0].subtract(a[1])));
            tr = tr.add(a[0]);
            tc = tc.add(a[1]);
            ts += stat.get(src)[0];
            tn += stat.get(src)[1];
        }
        t.total("Jami", ts, tn, fmt.amount(tr), fmt.amount(tc), fmt.pct(ratio(tc, tr)), fmt.amount(tr.subtract(tc)));
        t.note("Kelish sanasi davrda bo'lgan yashashlar. Komissiya — Exely bronidagi agent komissiyasi (mehmonxona valyutasida).");
        return t;
    }

    private ReportTable managers(Hotel hotel, Period p, String cur) {
        Map<String, Object[]> by = new TreeMap<>();   // [soni, summa, qaytarish soni, qaytarish summasi]
        for (PaymentRow r : data.payments(hotel.getId(), p)) {
            String u = r.username() == null || r.username().isBlank() ? "Noma'lum (Exely ko'rsatmagan)" : r.username();
            Object[] a = by.computeIfAbsent(u, k -> new Object[]{0, BigDecimal.ZERO, 0, BigDecimal.ZERO});
            if (r.refund()) {
                a[2] = (int) a[2] + 1;
                a[3] = ((BigDecimal) a[3]).add(r.amount());
            } else {
                a[0] = (int) a[0] + 1;
                a[1] = ((BigDecimal) a[1]).add(r.amount());
            }
        }
        ReportTable t = new ReportTable().col("Xodim (Exely foydalanuvchisi)").num("To'lovlar").num("Summa, " + cur)
                .num("Qaytarishlar").num("Qaytarish summasi, " + cur);
        int tc = 0, rc = 0;
        BigDecimal ts = BigDecimal.ZERO, rs = BigDecimal.ZERO;
        for (Map.Entry<String, Object[]> e : by.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Object[]> x) -> (BigDecimal) x.getValue()[1]).reversed()).toList()) {
            Object[] a = e.getValue();
            t.row(e.getKey(), a[0], fmt.amount((BigDecimal) a[1]), a[2], fmt.amount((BigDecimal) a[3]));
            tc += (int) a[0];
            ts = ts.add((BigDecimal) a[1]);
            rc += (int) a[2];
            rs = rs.add((BigDecimal) a[3]);
        }
        t.total("Jami", tc, fmt.amount(ts), rc, fmt.amount(rs));
        t.note("Exely API to'lovni kim qabul qilganini beradi; bronni kim yaratganini bermaydi.");
        return t;
    }

    // ================================================================ Moliya

    private ReportTable payments(Hotel hotel, Period p, String cur) {
        List<PaymentRow> rows = data.payments(hotel.getId(), p);
        ReportTable t = new ReportTable().col("Sana va vaqt").col("Bron").col("To'lov usuli").col("Qabul qildi")
                .col("Turi").num("Summa, " + cur);
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentRow r : rows) {
            t.row(r.paidAt().format(TIME), nz(r.bookingNumber()), nz(r.method()), nz(r.username()),
                    r.refund() ? cell("qaytarish", "neg") : "to'lov", fmt.amount(r.amount()));
            total = total.add(r.amount());
        }
        t.total("Jami: " + rows.size() + " ta", "", "", "", "", fmt.amount(total));
        return t;
    }

    private ReportTable paymentMethods(Hotel hotel, Period p, String cur) {
        Map<String, Object[]> by = new TreeMap<>();
        for (PaymentRow r : data.payments(hotel.getId(), p)) {
            Object[] a = by.computeIfAbsent(nz(r.method()), k -> new Object[]{0, BigDecimal.ZERO});
            a[0] = (int) a[0] + 1;
            a[1] = ((BigDecimal) a[1]).add(r.amount());
        }
        BigDecimal total = by.values().stream().map(a -> (BigDecimal) a[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        ReportTable t = new ReportTable().col("To'lov usuli").num("To'lovlar").num("Summa, " + cur).num("Ulushi");
        int count = 0;
        for (Map.Entry<String, Object[]> e : by.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Object[]> x) -> (BigDecimal) x.getValue()[1]).reversed()).toList()) {
            BigDecimal s = (BigDecimal) e.getValue()[1];
            t.row(e.getKey(), e.getValue()[0], fmt.amount(s), fmt.pct(ratio(s, total)));
            count += (int) e.getValue()[0];
        }
        t.total("Jami", count, fmt.amount(total), "100%");
        return t;
    }

    private ReportTable servicesSummary(Hotel hotel, Period p, String cur) {
        Map<String, Object[]> by = new TreeMap<>();
        for (ServiceRow r : data.extraServices(hotel.getId(), p)) {
            Object[] a = by.computeIfAbsent(nz(r.name()), k -> new Object[]{0, BigDecimal.ZERO, nz(r.category())});
            a[0] = (int) a[0] + 1;
            a[1] = ((BigDecimal) a[1]).add(r.amount());
        }
        BigDecimal total = by.values().stream().map(a -> (BigDecimal) a[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        ReportTable t = new ReportTable().col("Xizmat").col("Toifa").num("Qatorlar").num("Summa, " + cur).num("Ulushi");
        int count = 0;
        for (Map.Entry<String, Object[]> e : by.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Object[]> x) -> (BigDecimal) x.getValue()[1]).reversed()).toList()) {
            BigDecimal s = (BigDecimal) e.getValue()[1];
            t.row(e.getKey(), e.getValue()[2], e.getValue()[0], fmt.amount(s), fmt.pct(ratio(s, total)));
            count += (int) e.getValue()[0];
        }
        t.total("Jami", "", count, fmt.amount(total), "100%");
        t.note("Yashashdan tashqari xizmatlar (Exely PMS «Допуслуги»): nonushta, transfer, kir yuvish va h.k.");
        return t;
    }

    private ReportTable servicesDetail(Hotel hotel, Period p, String cur) {
        Map<LocalDate, Map<String, BigDecimal>> by = new TreeMap<>();
        for (ServiceRow r : data.extraServices(hotel.getId(), p)) {
            by.computeIfAbsent(r.date(), k -> new TreeMap<>()).merge(nz(r.name()), r.amount(), BigDecimal::add);
        }
        ReportTable t = new ReportTable().col("Sana / xizmat").num("Summa, " + cur);
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, Map<String, BigDecimal>> e : by.entrySet()) {
            BigDecimal day = e.getValue().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            t.rowOf("section", e.getKey().format(DAY) + " (" + weekday(e.getKey()) + ")", fmt.amount(day));
            e.getValue().forEach((name, sum) -> t.row(name, fmt.amount(sum)));
            total = total.add(day);
        }
        t.total("Jami", fmt.amount(total));
        return t;
    }

    private ReportTable balances(Hotel hotel, String cur) {
        DebtReport r = debtService.report(hotel, null, null, "debt");
        DebtReport.Summary s = r.summary();
        ReportTable t = new ReportTable().col("Toifa / bron").col("Mehmon").num("Yashashlar").num("Qarz, " + cur);
        t.rowOf("section", "Toifalar bo'yicha", "", s.count(), fmt.amount(s.total()));
        t.row(DebtReport.Category.IN_HOUSE.getLabel(), "", s.inHouseCount(), fmt.amount(s.inHouse()));
        t.row(DebtReport.Category.CHECKED_OUT.getLabel(), "", s.checkedOutCount(), fmt.amount(s.checkedOut()));
        t.row(DebtReport.Category.NOT_CHECKED_OUT.getLabel(), "", s.notCheckedOutCount(), fmt.amount(s.notCheckedOut()));
        t.rowOf("section", "Eng katta qarzdorlar", "", "", "");
        r.rows().stream().limit(15).forEach(x -> t.row(x.bookingNumber(), nz(x.guestName()), 1, fmt.amount(x.debt())));
        t.total("Jami qarz", "", s.count(), fmt.amount(s.total()));
        t.note("To'liq ro'yxat, tahlil va topshiriqlar — mehmonxona → Qarzdorlik sahifasida; hisoblar — Xizmatlar → Hisob-fakturalar.");
        return t;
    }

    // ================================================================ USALI

    /** USALI uchun davr ma'lumotlari: daromad (Exely), xarajatlar (kiritilgan oylar), statistika. */
    record Usali(StayMetrics s, Map<UsaliLine, BigDecimal> exp, BigDecimal otaCommission, int monthsEntered, int months,
                 long guestNights) {
        BigDecimal rooms() {
            return s.roomRevenue();
        }

        BigDecimal fb() {
            return s.mealsRevenue();
        }

        BigDecimal other() {
            return s.extrasRevenue().subtract(s.mealsRevenue());
        }

        BigDecimal revenue() {
            return s.totalRevenue();
        }

        BigDecimal e(UsaliLine l) {
            return exp.getOrDefault(l, BigDecimal.ZERO);
        }

        BigDecimal dept(UsaliLine.Dept d) {
            BigDecimal sum = UsaliLine.of(d).stream().map(this::e).reduce(BigDecimal.ZERO, BigDecimal::add);
            return d == UsaliLine.Dept.ROOMS ? sum.add(otaCommission) : sum;
        }

        BigDecimal departmentalProfit() {
            return revenue().subtract(dept(UsaliLine.Dept.ROOMS)).subtract(dept(UsaliLine.Dept.FB)).subtract(dept(UsaliLine.Dept.OTHER));
        }

        BigDecimal gop() {
            return departmentalProfit().subtract(dept(UsaliLine.Dept.UNDISTRIBUTED));
        }

        BigDecimal ebitda() {
            return gop().subtract(dept(UsaliLine.Dept.FEES)).subtract(dept(UsaliLine.Dept.NON_OPERATING));
        }

        boolean hasExpenses() {
            return monthsEntered > 0;
        }
    }

    private Usali usali(Hotel hotel, Period p) {
        StayMetrics s = kpiService.metrics(hotel, p);
        YearMonth from = YearMonth.from(p.from()), to = YearMonth.from(p.to());
        Map<UsaliLine, BigDecimal> exp = expenses.sum(hotel.getId(), from, to);
        int entered = expenses.monthsWithData(hotel.getId(), from, to);
        int months = (int) ChronoUnit.MONTHS.between(from, to) + 1;
        BigDecimal ota = BigDecimal.ZERO;
        long guestNights = 0;
        for (Stay x : data.overlapping(hotel.getId(), p)) {
            if (!x.active()) {
                continue;
            }
            long n = x.nightsIn(p);
            guestNights += n * Math.max(1, x.guests());
            if (x.commission() != null && n > 0) {
                ota = ota.add(x.commission().multiply(BigDecimal.valueOf(n)).divide(BigDecimal.valueOf(x.nights()), 2, RoundingMode.HALF_UP));
            }
        }
        return new Usali(s, exp, ota, entered, months, guestNights);
    }

    private void usaliNotes(ReportTable t, Usali u, Period p) {
        if (!u.hasExpenses()) {
            t.note("Xarajatlar hali kiritilmagan — faqat daromad (Exely). Kiritish: Hisobotlar → USALI xarajatlari.");
        } else if (u.monthsEntered() < u.months()) {
            t.note("Xarajatlar " + u.months() + " oydan " + u.monthsEntered() + " tasi uchun kiritilgan.");
        }
        if (!p.from().equals(p.from().withDayOfMonth(1)) || !p.to().equals(YearMonth.from(p.to()).atEndOfMonth())) {
            t.note("Xarajatlar to'liq oylar bo'yicha olinadi — USALI uchun «Shu oy» / «O'tgan oy» yoki butun oylarni tanlang.");
        }
        t.note("Rooms xarajatiga Exely'dagi OTA komissiyalari avtomatik qo'shiladi.");
    }

    private ReportTable usaliSummary(Hotel hotel, Period p, String cur) {
        Usali u = usali(hotel, p);
        long avail = u.s().availableRoomNights(), sold = u.s().soldRoomNights();
        ReportTable t = new ReportTable().col("Modda").num("Summa, " + cur).num("Daromaddan %").num("PAR, " + cur).num("POR, " + cur);
        Function<BigDecimal, Object[]> line = v -> new Object[]{fmt.amount(v), fmt.pct(ratio(v, u.revenue())),
                fmt.amount(div(v, avail)), fmt.amount(div(v, sold))};
        t.rowOf("section", "Operatsion daromad");
        t.row(concat("Rooms (yashash)", line.apply(u.rooms())));
        t.row(concat("Food & Beverage", line.apply(u.fb())));
        t.row(concat("Boshqa operatsion bo'limlar va daromadlar", line.apply(u.other())));
        t.rowOf("subtotal", concat("Jami operatsion daromad", line.apply(u.revenue())));
        t.rowOf("section", "Bo'lim xarajatlari");
        t.row(concat("Rooms", line.apply(u.dept(UsaliLine.Dept.ROOMS))));
        t.row(concat("Food & Beverage", line.apply(u.dept(UsaliLine.Dept.FB))));
        t.row(concat("Boshqa operatsion bo'limlar", line.apply(u.dept(UsaliLine.Dept.OTHER))));
        BigDecimal deptTotal = u.dept(UsaliLine.Dept.ROOMS).add(u.dept(UsaliLine.Dept.FB)).add(u.dept(UsaliLine.Dept.OTHER));
        t.rowOf("subtotal", concat("Jami bo'lim xarajatlari", line.apply(deptTotal)));
        t.rowOf("subtotal", concat("Bo'limlar foydasi (Total departmental profit)", line.apply(u.departmentalProfit())));
        t.rowOf("section", "Taqsimlanmagan operatsion xarajatlar");
        for (UsaliLine l : UsaliLine.of(UsaliLine.Dept.UNDISTRIBUTED)) {
            t.row(concat(l.getLabel(), line.apply(u.e(l))));
        }
        t.rowOf("subtotal", concat("Jami taqsimlanmagan xarajatlar", line.apply(u.dept(UsaliLine.Dept.UNDISTRIBUTED))));
        t.rowOf("subtotal", concat("GOP — yalpi operatsion foyda", line.apply(u.gop())));
        t.row(concat(UsaliLine.MGMT_FEES.getLabel(), line.apply(u.dept(UsaliLine.Dept.FEES))));
        t.rowOf("subtotal", concat("Nooperatsion xarajatlardan oldingi foyda", line.apply(u.gop().subtract(u.dept(UsaliLine.Dept.FEES)))));
        t.rowOf("section", "Nooperatsion xarajatlar");
        for (UsaliLine l : UsaliLine.of(UsaliLine.Dept.NON_OPERATING)) {
            t.row(concat(l.getLabel(), line.apply(u.e(l))));
        }
        t.total(concat("EBITDA", line.apply(u.ebitda())));
        usaliNotes(t, u, p);
        t.note("PAR — mavjud xona-kecha boshiga, POR — sotilgan xona-kecha boshiga.");
        return t;
    }

    private ReportTable usaliRooms(Hotel hotel, Period p, String cur) {
        Usali u = usali(hotel, p);
        Map<String, BigDecimal> seg = new LinkedHashMap<>();
        for (Stay s : data.overlapping(hotel.getId(), p)) {
            if (s.active()) {
                seg.merge(src(s), s.revenueIn(p), BigDecimal::add);
            }
        }
        BigDecimal segTotal = seg.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        ReportTable t = new ReportTable().col("Modda").num("Summa, " + cur).num("Rooms daromadidan %");
        t.rowOf("section", "Daromad — segmentlar (savdo kanallari) bo'yicha");
        seg.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()).forEach(e -> {
            // Segment ulushi bron narxidan; summa haqiqiy yashash daromadiga moslanadi.
            BigDecimal v = segTotal.signum() == 0 ? BigDecimal.ZERO
                    : u.rooms().multiply(e.getValue()).divide(segTotal, 0, RoundingMode.HALF_UP);
            t.row(e.getKey(), fmt.amount(v), fmt.pct(ratio(v, u.rooms())));
        });
        t.rowOf("subtotal", "Jami Rooms daromadi", fmt.amount(u.rooms()), "100%");
        t.rowOf("section", "Xarajatlar");
        t.row(UsaliLine.ROOMS_PAYROLL.getLabel(), fmt.amount(u.e(UsaliLine.ROOMS_PAYROLL)), fmt.pct(ratio(u.e(UsaliLine.ROOMS_PAYROLL), u.rooms())));
        t.row("OTA komissiyalari (Exely)", fmt.amount(u.otaCommission()), fmt.pct(ratio(u.otaCommission(), u.rooms())));
        t.row(UsaliLine.ROOMS_OTHER.getLabel(), fmt.amount(u.e(UsaliLine.ROOMS_OTHER)), fmt.pct(ratio(u.e(UsaliLine.ROOMS_OTHER), u.rooms())));
        BigDecimal exp = u.dept(UsaliLine.Dept.ROOMS);
        t.rowOf("subtotal", "Jami Rooms xarajatlari", fmt.amount(exp), fmt.pct(ratio(exp, u.rooms())));
        t.total("Rooms bo'limi foydasi", fmt.amount(u.rooms().subtract(exp)), fmt.pct(ratio(u.rooms().subtract(exp), u.rooms())));
        t.note("CPOR (sotilgan xona-kecha boshiga xarajat): " + fmt.amount(div(exp, u.s().soldRoomNights())) + " " + cur + ".");
        usaliNotes(t, u, p);
        return t;
    }

    private ReportTable usaliFb(Hotel hotel, Period p, String cur) {
        Usali u = usali(hotel, p);
        ReportTable t = new ReportTable().col("Modda").num("Summa, " + cur).num("F&B daromadidan %");
        t.row("Ovqatlanish daromadi (Exely: Meals / Food service)", fmt.amount(u.fb()), "100%");
        t.row(UsaliLine.FB_COST.getLabel(), fmt.amount(u.e(UsaliLine.FB_COST)), fmt.pct(ratio(u.e(UsaliLine.FB_COST), u.fb())));
        t.rowOf("subtotal", "Yalpi foyda", fmt.amount(u.fb().subtract(u.e(UsaliLine.FB_COST))),
                fmt.pct(ratio(u.fb().subtract(u.e(UsaliLine.FB_COST)), u.fb())));
        t.row(UsaliLine.FB_PAYROLL.getLabel(), fmt.amount(u.e(UsaliLine.FB_PAYROLL)), fmt.pct(ratio(u.e(UsaliLine.FB_PAYROLL), u.fb())));
        t.row(UsaliLine.FB_OTHER.getLabel(), fmt.amount(u.e(UsaliLine.FB_OTHER)), fmt.pct(ratio(u.e(UsaliLine.FB_OTHER), u.fb())));
        BigDecimal profit = u.fb().subtract(u.dept(UsaliLine.Dept.FB));
        t.total("F&B bo'limi foydasi", fmt.amount(profit), fmt.pct(ratio(profit, u.fb())));
        t.note("Sotilgan xona-kecha boshiga F&B daromadi: " + fmt.amount(div(u.fb(), u.s().soldRoomNights())) + " " + cur
                + "; mehmon-kecha boshiga: " + fmt.amount(div(u.fb(), u.guestNights())) + " " + cur + ".");
        usaliNotes(t, u, p);
        return t;
    }

    private ReportTable usaliOther(Hotel hotel, Period p, String cur) {
        Usali u = usali(hotel, p);
        Map<String, BigDecimal> by = new TreeMap<>();
        for (ServiceRow r : data.extraServices(hotel.getId(), p)) {
            if (!r.meals()) {
                by.merge(nz(r.name()), r.amount(), BigDecimal::add);
            }
        }
        ReportTable t = new ReportTable().col("Modda").num("Summa, " + cur).num("Ulushi");
        t.rowOf("section", "Daromad — xizmatlar bo'yicha");
        BigDecimal listed = by.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        by.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(e -> t.row(e.getKey(), fmt.amount(e.getValue()), fmt.pct(ratio(e.getValue(), listed))));
        t.rowOf("subtotal", "Jami boshqa daromad", fmt.amount(u.other()), "");
        t.row(UsaliLine.OTHER_COST.getLabel(), fmt.amount(u.e(UsaliLine.OTHER_COST)), fmt.pct(ratio(u.e(UsaliLine.OTHER_COST), u.other())));
        t.row(UsaliLine.OTHER_PAYROLL.getLabel(), fmt.amount(u.e(UsaliLine.OTHER_PAYROLL)), fmt.pct(ratio(u.e(UsaliLine.OTHER_PAYROLL), u.other())));
        BigDecimal profit = u.other().subtract(u.dept(UsaliLine.Dept.OTHER));
        t.total("Bo'limlar foydasi", fmt.amount(profit), fmt.pct(ratio(profit, u.other())));
        usaliNotes(t, u, p);
        return t;
    }

    private ReportTable usaliStats(Hotel hotel, Period p, String cur) {
        Usali u = usali(hotel, p);
        StayMetrics s = u.s();
        long avail = s.availableRoomNights(), sold = s.soldRoomNights();
        BigDecimal payroll = u.exp().entrySet().stream().filter(e -> e.getKey().isPayroll()).map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String na = "— (xarajat kiritilmagan)";
        ReportTable t = new ReportTable().col("Ko'rsatkich").num("Qiymat");
        t.rowOf("section", "Xonalar");
        t.row("Mavjud xona-kechalar", avail);
        t.row("Sotilgan xona-kechalar", sold);
        t.row("Bandlik (Occupancy)", fmt.pct(s.occupancy()));
        t.row("ADR, " + cur, fmt.amount(s.adr()));
        t.row("RevPAR, " + cur, fmt.amount(s.revpar()));
        t.row("TRevPAR (jami daromad / mavjud xona-kecha), " + cur, fmt.amount(div(u.revenue(), avail)));
        t.row("Sotilgan xona-kecha boshiga jami daromad, " + cur, fmt.amount(div(u.revenue(), sold)));
        t.rowOf("section", "Mehmonlar");
        t.row("Mehmon-kechalar", u.guestNights());
        t.row("Band xonadagi o'rtacha mehmonlar", fmt.number(sold == 0 ? 0 : (double) u.guestNights() / sold));
        t.row("O'rtacha yashash (ALOS), kecha", fmt.number(s.avgLengthOfStay()));
        t.row("Kelmagan (no-show)", s.noShows());
        t.rowOf("section", "Foyda va xarajat");
        t.row("GOP, " + cur, u.hasExpenses() ? fmt.amount(u.gop()) : na);
        t.row("GOP marjasi", u.hasExpenses() ? fmt.pct(ratio(u.gop(), u.revenue())) : na);
        t.row("GOPPAR, " + cur, u.hasExpenses() ? fmt.amount(div(u.gop(), avail)) : na);
        t.row("CPOR (Rooms xarajati / sotilgan xona-kecha), " + cur, fmt.amount(div(u.dept(UsaliLine.Dept.ROOMS), sold)));
        t.row("Ish haqi daromaddan %", u.hasExpenses() ? fmt.pct(ratio(payroll, u.revenue())) : na);
        t.row("EBITDA, " + cur, u.hasExpenses() ? fmt.amount(u.ebitda()) : na);
        usaliNotes(t, u, p);
        return t;
    }

    // ================================================================ yordamchilar

    /**
     * Yashashning davrdagi yashash daromadi: bron narxi (ichida nonushta va boshqa xizmatlar ham bo'lishi mumkin)
     * davr kechalariga bo'linadi va jami mehmonxona sahifasidagi yashash daromadiga moslanadi —
     * shunda manba/xona turi bo'yicha ADR asosiy ko'rsatkichlardagi ADR bilan bir xil chiqadi.
     */
    private Function<Stay, BigDecimal> roomRevenueOf(Hotel hotel, Period p, List<Stay> stays) {
        BigDecimal booked = stays.stream().filter(Stay::active).map(s -> s.revenueIn(p)).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (booked.signum() == 0) {
            return s -> BigDecimal.ZERO;
        }
        BigDecimal factor = kpiService.metrics(hotel, p).roomRevenue().divide(booked, 12, RoundingMode.HALF_UP);
        return s -> s.revenueIn(p).multiply(factor).setScale(0, RoundingMode.HALF_UP);
    }

    private LocalDate clampToday(Period p) {
        LocalDate today = kpiService.today();
        return today.isBefore(p.from()) ? p.from() : today.isAfter(p.to()) ? p.to() : today;
    }

    private String src(Stay s) {
        return s.source() == null || s.source().isBlank() ? "Noma'lum" : s.source();
    }

    private ReportTable.Cell debtCell(BigDecimal v) {
        return cell(fmt.amount(v), v.signum() > 0 ? "neg" : null);
    }

    private ReportTable.Cell heat(double occupancy) {
        int level = occupancy >= 0.9 ? 4 : occupancy >= 0.7 ? 3 : occupancy >= 0.5 ? 2 : occupancy >= 0.3 ? 1 : 0;
        return cell(fmt.pct(occupancy), "heat-" + level);
    }

    private ReportTable.Cell pickup(Integer v) {
        if (v == null) {
            return cell("—", "muted");
        }
        return cell(v > 0 ? "+" + v : v < 0 ? "−" + (-v) : "0", v > 0 ? "pos" : v < 0 ? "neg" : null);
    }

    private String change(long now, long prev) {
        return nz(fmt.change(now, prev));
    }

    static String weekday(LocalDate d) {
        return WEEKDAYS[d.getDayOfWeek().getValue() - 1];
    }

    static boolean weekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    static String status(BookingStatus s) {
        return switch (s) {
            case CONFIRMED -> "Tasdiqlangan";
            case CHECKED_IN -> "Yashayapti";
            case CHECKED_OUT -> "Ketgan";
            case CANCELLED -> "Bekor qilingan";
            case NO_SHOW -> "Kelmagan";
        };
    }

    /** Xona raqamlari: raqam bo'yicha (102 < 1010), keyin matn. */
    static Comparator<String> roomOrder() {
        return Comparator.comparing((String s) -> s.isEmpty() || s.equals("—") ? 1 : 0)
                .thenComparing(s -> s.replaceAll("\\D", "").isEmpty() ? Long.MAX_VALUE : Long.parseLong(s.replaceAll("\\D", "")
                        .substring(0, Math.min(15, s.replaceAll("\\D", "").length()))))
                .thenComparing(Comparator.naturalOrder());
    }

    static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    static double ratio(long a, long b) {
        return b == 0 ? 0 : (double) a / b;
    }

    static double ratio(BigDecimal a, BigDecimal b) {
        return b == null || b.signum() == 0 ? 0 : a.doubleValue() / b.doubleValue();
    }

    static BigDecimal div(BigDecimal a, long b) {
        return b == 0 ? BigDecimal.ZERO : a.divide(BigDecimal.valueOf(b), 0, RoundingMode.HALF_UP);
    }

    private static Object[] concat(String first, Object[] rest) {
        Object[] r = new Object[rest.length + 1];
        r[0] = first;
        System.arraycopy(rest, 0, r, 1, rest.length);
        return r;
    }
}
