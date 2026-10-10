--liquibase formatted sql

--changeset behzod:30
-- Faktura.uz (elektron hujjat aylanishi) ulanishi — mehmonxona bo'yicha. Parol, client secret va token shifrlangan.
CREATE TABLE faktura_accounts
(
    hotel_id      BIGINT       NOT NULL PRIMARY KEY,
    company_inn   VARCHAR(9)   NOT NULL,
    username      VARCHAR(128) NOT NULL,
    password      VARCHAR(512) NULL,
    client_id     VARCHAR(128) NOT NULL,
    client_secret VARCHAR(512) NULL,
    access_token  TEXT         NULL,
    token_expires DATETIME(6)  NULL,
    synced_at     DATETIME(6)  NULL,
    sync_ok       BOOLEAN      NULL,
    sync_message  VARCHAR(500) NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT fk_faktura_accounts_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE
);

-- Hujjatlar: IN — yetkazib beruvchilardan kelgan (xarajat), OUT — mehmonxona mijozlarga chiqargan (rasmiy hisob-faktura).
-- counterparty — kiruvchida sotuvchi, chiquvchida xaridor. usali_line — kiruvchi qaysi USALI xarajat moddasiga
-- (yetkazib beruvchi qoidasi yoki qo'lda). booking_number — chiquvchi qaysi bronga (avtomatik yoki qo'lda).
-- payload — Faktura.uz'dagi xom JSON.
CREATE TABLE faktura_documents
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id          BIGINT         NOT NULL,
    unique_id         VARCHAR(64)    NOT NULL,
    direction         VARCHAR(3)     NOT NULL,
    doc_type          INT            NULL,
    status            INT            NULL,
    number            VARCHAR(100)   NULL,
    doc_date          DATE           NULL,
    counterparty_inn  VARCHAR(20)    NULL,
    counterparty_name VARCHAR(255)   NULL,
    total             DECIMAL(17, 2) NULL,
    vat               DECIMAL(17, 2) NULL,
    total_with_vat    DECIMAL(17, 2) NULL,
    usali_line        VARCHAR(48)    NULL,
    line_source       VARCHAR(8)     NULL,
    booking_number    VARCHAR(64)    NULL,
    match_source      VARCHAR(8)     NULL,
    payload           MEDIUMTEXT     NOT NULL,
    fetched_at        DATETIME(6)    NOT NULL,
    CONSTRAINT fk_faktura_documents_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT uq_faktura_documents UNIQUE (hotel_id, unique_id),
    INDEX idx_faktura_documents_date (hotel_id, direction, doc_date),
    INDEX idx_faktura_documents_booking (hotel_id, booking_number)
);

-- Yetkazib beruvchi (INN) → USALI moddasi: bir marta belgilansa, uning keyingi fakturalari avtomatik shu moddaga.
CREATE TABLE faktura_rules
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id   BIGINT      NOT NULL,
    seller_inn VARCHAR(20) NOT NULL,
    usali_line VARCHAR(48) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_faktura_rules_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT uq_faktura_rules UNIQUE (hotel_id, seller_inn)
);

--changeset behzod:31
-- ЭСФ operatori mehmonxona bo'yicha tanlanadi: DIDOX (INN + parol; HotelPulse'ning hamkor tokeni sozlamada)
-- yoki FAKTURA (login, parol, client id/secret). Didox'da login va client id yo'q.
ALTER TABLE faktura_accounts ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'FAKTURA' AFTER hotel_id;
ALTER TABLE faktura_accounts MODIFY username VARCHAR(128) NULL;
ALTER TABLE faktura_accounts MODIFY client_id VARCHAR(128) NULL;
