--liquibase formatted sql

--changeset behzod:39
-- Chorvoq Stay sayti (chorvoqstay.uz) so'rovlari: bron, rezident (kvartira egasi), chat orqali qo'ng'iroq.
-- Avval PHP Telegram'ga yuborardi va hech qayerda saqlanmasdi — endi bazada, admin'da ko'rinadi.
CREATE TABLE stay_requests
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    kind       VARCHAR(64)  NULL,
    name       VARCHAR(150) NULL,
    phone      VARCHAR(40)  NULL,
    subject    VARCHAR(255) NULL,
    payload    MEDIUMTEXT   NOT NULL,
    lang       VARCHAR(8)   NULL,
    ip         VARCHAR(64)  NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'NEW',
    created_at DATETIME(6)  NOT NULL,
    handled_at DATETIME(6)  NULL,
    handled_by VARCHAR(100) NULL,
    INDEX idx_stay_requests_created (created_at)
);
