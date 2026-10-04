# HotelPulse

Mehmonxona egalari uchun asosiy ko'rsatkichlar paneli: bandlik, ADR, RevPAR, daromad va qarzdorlik.
Ma'lumotlar Exely'dan avtomatik olinadi. Telegram bot orqali kunlik hisobot ham yuboriladi (rejada).

## Rollar

| Rol | Ko'radi |
|---|---|
| `OWNER` | Barcha mehmonxonalar, mehmonxona va foydalanuvchi boshqaruvi |
| `HOTEL_OWNER` | Faqat o'ziga biriktirilgan mehmonxonalar |
| `HOTEL_STAFF` | Faqat o'ziga biriktirilgan mehmonxonalar (ko'rish) |

## Texnologiyalar

Java 17, Spring Boot 4, Spring Security, Spring Data JPA, Liquibase, MySQL 8, Thymeleaf, Chart.js.

## Lokal ishga tushirish

MySQL ishlab turgan bo'lishi kerak. `hotel_pulse` bazasi avtomatik yaratiladi.
Maxfiy qiymatlar loyiha ildizidagi `.env` faylidan o'qiladi (namuna: `.env.example`).

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Sayt: http://localhost:8081

`local` profilida:
- shablon va CSS/JS o'zgarishlari serverni qayta ishga tushirmasdan ko'rinadi;
- `/dev-login?user=owner` — parolsiz kirish (faqat shu kompyuterdan; boshqa profillarda bu manzil mavjud emas).

Birinchi ishga tushishda `owner` foydalanuvchisi yaratiladi. `OWNER_PASSWORD` berilmasa,
vaqtinchalik parol logga bir marta chiqariladi.

## Exely integratsiyasi

Read Reservation API (Exely Connect, OAuth2 client credentials) orqali bronlar har 30 daqiqada sinxronlanadi.

Mehmonxona Exely extranet'da: **Property settings → API connections → Create a connection** →
**Public APIs** yorlig'ida **Read Reservation API** → **Save and enable access**. Hosil bo'lgan
`client_id` va `client_secret` hamda mehmonxona ID admin panelda mehmonxona sahifasiga kiritiladi.

- Birinchi sinxronlash oxirgi `EXELY_INITIAL_DAYS` kunda o'zgargan bronlarni oladi, keyingilari — faqat yangi o'zgarishlarni (`continueToken`).
- Bitta bronning har bir xona-yashashi alohida qator; narx — `priceAfterTax` (xizmatlarsiz, faqat xona daromadi).
- Read Reservation API faqat oldindan to'lovni beradi — qarzdorlik Exely PMS API ulanganda ko'rsatiladi.

## Telegram bot

1. Telegram'da [@BotFather](https://t.me/BotFather) → `/newbot` → nom va username bering → tokenni oling.
2. Tokenni `TELEGRAM_BOT_TOKEN` ga yozing (lokal — `.env`). Server qayta ishga tushganda bot ulanadi.
3. Saytda **Profil → Telegram'ni ulash** — bir martalik havola (15 daqiqa) botni ochadi, **Start** bosiladi.

Bitta token bilan faqat bitta server ishlay oladi — lokal va production uchun **alohida bot** yarating.

Buyruqlar: `/bugun`, `/hafta`, `/oy`, `/qarzlar`, `/hisobot` (kunlik hisobotni yoqish/o'chirish), `/uzish`, `/yordam`.
Har kuni 09:00 da (APP_ZONE) kechagi kun hisoboti yuboriladi. Ruxsatlar saytdagi bilan bir xil.

## Muhit o'zgaruvchilari

| O'zgaruvchi | Tavsif |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL ulanishi |
| `SERVER_PORT` | Port (standart: 8081) |
| `OWNER_USERNAME`, `OWNER_PASSWORD` | Birinchi OWNER foydalanuvchisi |
| `APP_ENCRYPTION_KEY` | Exely client_secret'larini shifrlash uchun base64 32 baytlik kalit. Production'da majburiy |
| `APP_ZONE` | Mehmonxona vaqt zonasi (standart: Asia/Tashkent) |
| `EXELY_INITIAL_DAYS` | Birinchi sinxronlash chuqurligi, kun (standart: 400) |
| `EXELY_SYNC_INTERVAL` | Avtomatik sinxronlash oralig'i (standart: PT30M) |
| `EXELY_SCHEDULER_ENABLED` | Avtomatik sinxronlash (standart: true) |
| `EXELY_REQUEST_DELAY` | So'rovlar orasidagi pauza (standart: 120ms) |
| `TELEGRAM_BOT_TOKEN` | @BotFather tokeni (bo'sh — bot o'chirilgan) |
| `APP_SITE_URL` | Saytning ommaviy manzili — botdagi "Saytda batafsil" tugmasi uchun |
| `TELEGRAM_DAILY_REPORT_CRON` | Kunlik hisobot vaqti (standart: `0 0 9 * * *`) |

Kalit yaratish: `openssl rand -base64 32`

## Testlar

```bash
./mvnw test                      # bazasiz testlar
./mvnw test -Dtest.excluded=     # + lokal MySQL'dagi hotel_pulse_test bazasi bilan testlar
```

## Yo'l xaritasi

1. ✅ Skelet: kirish, rollar, mehmonxona va foydalanuvchi boshqaruvi
2. ✅ Ko'rsatkichlar paneli (KPI va grafiklar)
3. ✅ Exely sinxronizatsiyasi (Read Reservation API)
4. ✅ Telegram bot
5. Docker va server
