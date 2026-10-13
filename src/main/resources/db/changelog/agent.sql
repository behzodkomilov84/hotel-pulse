--liquibase formatted sql

--changeset behzod:32
-- Mehmonxona kompyuterlaridagi agentlar (UCS Shelter, keyin 1C). Juftlash: agent kod oladi, platforma egasi uni
-- admin sahifada tasdiqlab mehmonxonaga bog'laydi; agent kalitni bir marta oladi (bazada faqat SHA-256 xeshi).
CREATE TABLE agents
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    kind            VARCHAR(16)  NOT NULL,
    machine         VARCHAR(128) NULL,
    hotel_hint      VARCHAR(150) NULL,
    hotel_id        BIGINT       NULL,
    pairing_id      VARCHAR(64)  NOT NULL,
    pairing_code    VARCHAR(8)   NOT NULL,
    pairing_expires DATETIME(6)  NOT NULL,
    key_hash        VARCHAR(64)  NULL,
    pending_key     VARCHAR(512) NULL,
    approved_at     DATETIME(6)  NULL,
    last_seen_at    DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    CONSTRAINT fk_agents_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT uq_agents_pairing UNIQUE (pairing_id),
    INDEX idx_agents_key (key_hash)
);

-- Agent yuklagan fayllar (tuzilish, keyin jadvallar ma'lumoti) — xom holda.
CREATE TABLE agent_uploads
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id   BIGINT       NOT NULL,
    hotel_id   BIGINT       NOT NULL,
    kind       VARCHAR(32)  NOT NULL,
    name       VARCHAR(128) NULL,
    size_bytes BIGINT       NOT NULL,
    payload    LONGBLOB     NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT fk_agent_uploads_agent FOREIGN KEY (agent_id) REFERENCES agents (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_uploads_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    INDEX idx_agent_uploads (hotel_id, kind, created_at)
);

--changeset behzod:33
-- Agent sinxronlashi: har bir jadvalning oxirgi to'liq nusxasi (gzip JSON Lines, katta jadval — qismlarga bo'lingan).
-- Har sinxronlashda almashtiriladi (tarix saqlanmaydi — hajm o'smasin).
CREATE TABLE agent_tables
(
    hotel_id    BIGINT       NOT NULL,
    table_name  VARCHAR(64)  NOT NULL,
    part        INT          NOT NULL,
    parts       INT          NOT NULL,
    row_count   INT          NOT NULL,
    sync_id     VARCHAR(40)  NULL,
    payload     LONGBLOB     NOT NULL,
    uploaded_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (hotel_id, table_name, part),
    CONSTRAINT fk_agent_tables_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE
);

ALTER TABLE agents ADD COLUMN last_sync_at DATETIME(6) NULL;
ALTER TABLE agents ADD COLUMN last_sync_message VARCHAR(500) NULL;

--changeset behzod:34
-- Korxona va guruh hisoblari qarzi (Shelter: to'lovchi korxona folio'lari, KIND=1, BALANCE < 0).
-- Mehmon (yashash) darajasidan tashqari — har importda to'liq almashtiriladi.
CREATE TABLE company_debts
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id    BIGINT         NOT NULL,
    external_id VARCHAR(64)    NOT NULL,
    name        VARCHAR(255)   NOT NULL,
    amount      DECIMAL(15, 2) NOT NULL,
    opened_on   DATE           NULL,
    updated_at  DATETIME(6)    NOT NULL,
    CONSTRAINT fk_company_debts_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT uq_company_debts UNIQUE (hotel_id, external_id)
);

--changeset behzod:35
-- Korxona hisobining tafsiloti: xizmatlar, to'lovlar, yashashlar davri, xona/kishi soni va yashashlar ro'yxati (JSON).
ALTER TABLE company_debts
    ADD COLUMN services    DECIMAL(15, 2) NOT NULL DEFAULT 0,
    ADD COLUMN paid        DECIMAL(15, 2) NOT NULL DEFAULT 0,
    ADD COLUMN period_from DATE           NULL,
    ADD COLUMN period_to   DATE           NULL,
    ADD COLUMN rooms       INT            NOT NULL DEFAULT 0,
    ADD COLUMN guests      INT            NOT NULL DEFAULT 0,
    ADD COLUMN stays_json  MEDIUMTEXT     NULL;
