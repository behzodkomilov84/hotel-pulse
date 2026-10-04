--liquibase formatted sql

--changeset behzod:4
-- Mehmonxonaning asosiy valyutasi — barcha KPI'lar shu valyutada hisoblanadi.
ALTER TABLE hotels
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'UZS';

--changeset behzod:5
-- Bronlar. origin — ma'lumot qayerdan kelgani: DEMO (sinov), EXELY, MANUAL.
-- (hotel_id, origin, external_id) — Exely'dan qayta sinxronlashda takror
-- yozuv paydo bo'lmasligi uchun.
-- total_amount — butun yashash davri uchun xona narxi (barcha kechalar).
CREATE TABLE bookings
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id       BIGINT         NOT NULL,
    origin         VARCHAR(16)    NOT NULL,
    external_id    VARCHAR(64)    NOT NULL,
    source         VARCHAR(64)    NOT NULL,
    status         VARCHAR(16)    NOT NULL,
    guest_name     VARCHAR(150),
    arrival_date   DATE           NOT NULL,
    departure_date DATE           NOT NULL,
    rooms          INT            NOT NULL DEFAULT 1,
    guests         INT            NOT NULL DEFAULT 1,
    total_amount   DECIMAL(15, 2) NOT NULL,
    booked_at      DATETIME(6)    NOT NULL,
    cancelled_at   DATETIME(6),
    CONSTRAINT fk_bookings_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT uq_bookings_external UNIQUE (hotel_id, origin, external_id),
    INDEX idx_bookings_stay (hotel_id, arrival_date, departure_date),
    INDEX idx_bookings_booked (hotel_id, booked_at)
);

--changeset behzod:6
-- To'lovlar (bronga bog'langan yoki bog'lanmagan).
CREATE TABLE payments
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id    BIGINT         NOT NULL,
    booking_id  BIGINT,
    origin      VARCHAR(16)    NOT NULL,
    external_id VARCHAR(64)    NOT NULL,
    amount      DECIMAL(15, 2) NOT NULL,
    method      VARCHAR(32),
    paid_at     DATETIME(6)    NOT NULL,
    CONSTRAINT fk_payments_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES bookings (id) ON DELETE CASCADE,
    CONSTRAINT uq_payments_external UNIQUE (hotel_id, origin, external_id),
    INDEX idx_payments_paid (hotel_id, paid_at)
);
