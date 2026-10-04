--liquibase formatted sql

--changeset behzod:1
-- Tizim foydalanuvchilari. role: OWNER (platforma egasi — hamma narsani
-- ko'radi), HOTEL_OWNER (mehmonxona egasi), HOTEL_STAFF (faqat ko'rish).
CREATE TABLE users
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL UNIQUE,
    password   VARCHAR(100) NOT NULL,
    full_name  VARCHAR(150),
    phone      VARCHAR(32),
    role       VARCHAR(20)  NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

--changeset behzod:2
-- Mehmonxonalar. exely_api_key — bazada AES-GCM bilan SHIFRLANGAN holda
-- saqlanadi (EncryptedStringConverter); kaliti yo'q mehmonxona ham
-- qo'shilaveradi (keyin Exely'siz, qo'lda/boshqa manbadan to'ldiriladi).
CREATE TABLE hotels
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(150) NOT NULL,
    city              VARCHAR(100),
    rooms_count       INT          NOT NULL DEFAULT 0,
    exely_property_id VARCHAR(64),
    exely_api_key     VARCHAR(1024),
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

--changeset behzod:3
-- Qaysi foydalanuvchi qaysi mehmonxona(lar)ni ko'ra oladi. Bitta egada
-- bir nechta mehmonxona, bitta mehmonxonada bir nechta xodim bo'lishi mumkin.
CREATE TABLE user_hotels
(
    user_id  BIGINT NOT NULL,
    hotel_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, hotel_id),
    CONSTRAINT fk_user_hotels_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_hotels_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE
);
