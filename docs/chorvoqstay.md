# Chorvoq Stay (chorvoqstay.uz) — HotelPulse ichida

Mehmonlar sayti (chorvoqstay.uz) va mulkdorlar dashboardi (hotel-pulse.uz) — **bitta Java ilova va bitta baza**.
Ilova so'rov qaysi domendan kelganini ko'radi: `chorvoqstay.uz` → mehmonlar sayti, `hotel-pulse.uz` → dashboard va admin.
Biznes talablari: `D:\JavaPrograms\chorvoqstay.uz\roadmap.md` (oferta, agentlik va joylashtirish shartnomalari).

```
chorvoqstay.uz ─┐
                ├─► nginx ─► HotelPulse (Spring Boot) ─► MySQL
hotel-pulse.uz ─┘             ├ /api/stay/**  — sayt API (sessiyasiz)
                              ├ /stay/**      — sayt fayllari (chorvoqstay.uz'da — ildizda)
                              └ /admin/stay/** — so'rovlar va keyinchalik kvartiralar, narxlar
```

## Bosqichlar

| # | Bosqich | Mazmuni | Holat |
|---|---------|---------|-------|
| 1 | **Ko'chirish** | Sayt fayllari HotelPulse'ga (`static/stay`), domen bo'yicha yo'naltirish; PHP (`api/telegram.php`) o'rniga Java API: so'rovlar bazaga yoziladi va Telegram'ga boradi; admin'da so'rovlar ro'yxati; chorvoqstay.uz DNS → 62.238.102.84, nginx (`/opt/studygrow/nginx/templates/chorvoqstay.conf.template`) + Let's Encrypt | **bajarildi (2026-10-17)** |
| 2 | **Kvartiralar va narxlar** | Chorvoq Stay — HotelPulse'dagi mehmonxona; kvartiralar (bino, raqam, maydon, toifa), toifalar, kunlik va minimal narxlar admin'da; saytdagi xonalar va narxlar bazadan | |
| 3 | **Bron oqimi** | Bo'sh kunlar kalendari, narx hisobi (turistik yig'im, depozit alohida), oferta va shaxsiy ma'lumotlar roziligi belgilari, bron holatlari, overbooking'dan himoya, tasdiq Telegram/SMS | |
| 4 | **Mulkdor kabineti** | Yangi rol — faqat o'z kvartirasi; oylik hisobot (60/40, 12% JShDS), to'lovlar, "o'z kunlarim", e'tiroz | |
| 5 | **To'lov va kanallar** | Payme / Click / Uzum; Booking va Airbnb (iCal yoki channel manager) | |

## Ochiq masalalar
- Shaxsiy ma'lumotlar O'zbekistondagi serverda saqlanishi kerak (onlayn check-in, pasport) — hozirgi server joylashuvi tekshiriladi.
- Bitta deploy: HotelPulse yangilanganda sayt ~40 s qayta ishga tushadi.

## Infratuzilma (1-bosqichdan keyin)
- DNS: ahost.uz (cPanel `hospita1` → Zone Editor). `chorvoqstay.uz` A → 62.238.102.84 (HotelPulse serveri), `www` — CNAME.
- Pochta va FTP eski hostingda qoldi: MX → `mail.chorvoqstay.uz` (A 37.153.159.11), `ftp` → `mail.chorvoqstay.uz`.
  Eski hosting **o'chirilmaydi** — `info@chorvoqstay.uz` pochtasi u yerda.
- Domenni boshqa shaxsga o'tkazish (perereg.) — `.uz` qoidasi 100-band: ro'yxatdan o'tgandan 6 oy keyin (chorvoqstay.uz — 24.03.2027 dan).
