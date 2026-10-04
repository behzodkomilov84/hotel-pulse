# HotelPulse

Mehmonxona egalari uchun asosiy ko'rsatkichlar paneli: bandlik, ADR, RevPAR, daromad va qarzdorlik.
Ma'lumotlar Exely PMS'dan olinadi. Telegram bot orqali kunlik hisobot ham yuboriladi.

## Rollar

| Rol | Ko'radi |
|---|---|
| `OWNER` | Barcha mehmonxonalar, mehmonxona va foydalanuvchi boshqaruvi |
| `HOTEL_OWNER` | Faqat o'ziga biriktirilgan mehmonxonalar |
| `HOTEL_STAFF` | Faqat o'ziga biriktirilgan mehmonxonalar (ko'rish) |

## Texnologiyalar

Java 17, Spring Boot 4, Spring Security, Spring Data JPA, Liquibase, MySQL 8, Thymeleaf.

## Lokal ishga tushirish

MySQL ishlab turgan bo'lishi kerak. `hotel_pulse` bazasi avtomatik yaratiladi.

```bash
DB_PASSWORD=... ./mvnw spring-boot:run
```

Sayt: http://localhost:8081

Birinchi ishga tushishda `owner` foydalanuvchisi yaratiladi. `OWNER_PASSWORD` berilmasa,
vaqtinchalik parol logga bir marta chiqariladi.

## Muhit o'zgaruvchilari

| O'zgaruvchi | Tavsif |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL ulanishi |
| `SERVER_PORT` | Port (standart: 8081) |
| `OWNER_USERNAME`, `OWNER_PASSWORD` | Birinchi OWNER foydalanuvchisi |
| `APP_ENCRYPTION_KEY` | Exely API kalitlarini shifrlash uchun base64 32 baytlik kalit. Production'da majburiy |

Kalit yaratish: `openssl rand -base64 32`

## Yo'l xaritasi

1. ✅ Skelet: kirish, rollar, mehmonxona va foydalanuvchi boshqaruvi
2. Ko'rsatkichlar paneli (KPI va grafiklar)
3. Exely sinxronizatsiyasi
4. Telegram bot
5. Docker va server
