--liquibase formatted sql

--changeset behzod:11
-- Exely PMS "Universal API" (TravelLine WebPMS) — mehmonxona extranet'idagi
-- "Управление отелем → Настройки → Интеграции → Ключ интеграции" kaliti.
-- AES-GCM bilan shifrlangan holda saqlanadi.
-- pms_bookings_synced_until — bronlar shu paytgacha (modifiedTo) olingan;
-- pms_payments_synced_until — to'lovlar shu paytgacha olingan.
ALTER TABLE hotels
    ADD COLUMN exely_pms_key             VARCHAR(1024) NULL AFTER exely_client_secret,
    ADD COLUMN pms_bookings_synced_until DATETIME(6)   NULL,
    ADD COLUMN pms_payments_synced_until DATETIME(6)   NULL;

--changeset behzod:12
-- PMS bergan haqiqiy to'lanmagan qoldiq (roomStay.totalPrice.toPayAmount).
-- NULL — manba qoldiqni bermaydi (demo/Read Reservation), u holda qarz
-- to'lovlar jadvalidan hisoblanadi.
ALTER TABLE bookings
    ADD COLUMN balance_due DECIMAL(15, 2) NULL;

--changeset behzod:13
-- Boshqa valyutadagi (USD) bron va to'lovlar endi so'mga o'giriladi — ilgari noto'g'ri
-- (o'girilmasdan) yozilganlari to'g'rilanishi uchun PMS ma'lumotlari boshidan qayta olinadi.
UPDATE hotels
SET pms_bookings_synced_until = NULL,
    pms_payments_synced_until = NULL
WHERE exely_pms_key IS NOT NULL;

--changeset behzod:14
-- Xonalar soni odatda Exely /rooms ro'yxatidan olinadi; ro'yxatda sotilmaydigan xonalar ham
-- bo'lishi mumkin — shunda admin sonni qo'lda belgilaydi va sinxronlash uni o'zgartirmaydi.
ALTER TABLE hotels
    ADD COLUMN rooms_count_manual BOOLEAN NOT NULL DEFAULT FALSE AFTER rooms_count;

--changeset behzod:15
-- Exely PMS "/analytics/services" (dateKind=1 — yashash kuni bo'yicha): har kunlik xizmatlar
-- (kind: 0 — yashash, 1 — qo'shimcha xizmat/nonushta, 2 — transfer, 3/4 — erta/kech).
-- Yashash daromadi, ADR va RevPAR Exely ta'rifi bo'yicha shundan hisoblanadi.
CREATE TABLE service_revenue
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id       BIGINT         NOT NULL,
    external_id    VARCHAR(64)    NOT NULL,
    service_date   DATE           NOT NULL,
    kind           TINYINT        NOT NULL,
    name           VARCHAR(128)   NULL,
    amount         DECIMAL(15, 2) NOT NULL,
    booking_number VARCHAR(64)    NULL,
    CONSTRAINT fk_service_revenue_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    INDEX idx_service_revenue_hotel_date (hotel_id, service_date)
);
-- Xizmatlar ma'lumoti qamrab olgan sanalar [from, until]; bu oraliqdan tashqarida daromad bronlardan hisoblanadi.
ALTER TABLE hotels
    ADD COLUMN pms_services_from  DATE NULL,
    ADD COLUMN pms_services_until DATE NULL;
-- Bronning asl valyutasi (Exely currencyId) — xizmatlar summasi qaysi valyutada ekanini aniqlash uchun.
ALTER TABLE bookings
    ADD COLUMN currency VARCHAR(3) NULL;
-- Bronlarga valyuta yozilishi uchun PMS bronlari qayta olinadi (to'lovlar emas).
UPDATE hotels
SET pms_bookings_synced_until = NULL
WHERE exely_pms_key IS NOT NULL;
