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

--changeset behzod:40
-- Chorvoq Stay 2-bosqich: toifalar (sayt xonalari), narx davrlari va kvartiralar.
-- stay_settings — yagona qator: Chorvoq Stay'ning HotelPulse'dagi mehmonxonasi (bronlar va KPI uchun; ilova o'zi yaratadi).
CREATE TABLE stay_settings
(
    id       INT PRIMARY KEY,
    hotel_id BIGINT NULL
);
INSERT INTO stay_settings (id, hotel_id) VALUES (1, NULL);

-- Toifa = saytdagi xona turi (kod sayt bilan bir xil: studio, one, two, family, grand).
-- base_price — kechalik narx (davr belgilanmagan kunlarda); min_price — mulkdorlar bilan kelishilgan eng past narx.
CREATE TABLE stay_categories
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(32)    NOT NULL,
    name       VARCHAR(100)   NOT NULL,
    area_text  VARCHAR(40)    NULL,
    capacity   INT            NOT NULL,
    extra_beds INT            NOT NULL DEFAULT 0,
    base_price DECIMAL(15, 2) NOT NULL,
    min_price  DECIMAL(15, 2) NULL,
    active     BOOLEAN        NOT NULL DEFAULT TRUE,
    sort       INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_stay_categories_code UNIQUE (code)
);
INSERT INTO stay_categories (code, name, area_text, capacity, extra_beds, base_price, sort) VALUES
    ('studio', 'Studio', '26 m²', 2, 0, 600000, 1),
    ('one', 'One-Bedroom Suite', '38–46 m²', 2, 2, 850000, 2),
    ('two', 'Two-Bedroom Suite', '58 m²', 4, 2, 1200000, 3),
    ('family', 'Family Suite', '90 m²', 6, 2, 1900000, 4),
    ('grand', 'Grand Terrace Residence', '122 m²', 8, 2, 2600000, 5);

-- Mavsum/bayram narxlari: [date_from, date_to] kunlari kechalik narx (bitta toifada davrlar ustma-ust tushmaydi).
CREATE TABLE stay_rate_periods
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT         NOT NULL,
    date_from   DATE           NOT NULL,
    date_to     DATE           NOT NULL,
    price       DECIMAL(15, 2) NOT NULL,
    note        VARCHAR(255)   NULL,
    created_at  DATETIME(6)    NOT NULL,
    CONSTRAINT fk_stay_rates_category FOREIGN KEY (category_id) REFERENCES stay_categories (id) ON DELETE CASCADE,
    INDEX idx_stay_rates_dates (category_id, date_from, date_to)
);

-- Kvartira (Chorvoq Darvozasi majmuasida): uy va kvartira raqami bo'yicha yagona.
-- status: ACTIVE — sotuvda, PREPARING — tayyorlanmoqda (remont, jihozlash), INACTIVE — chiqarilgan.
CREATE TABLE stay_units
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT        NOT NULL,
    building    VARCHAR(20)   NOT NULL,
    number      VARCHAR(20)   NOT NULL,
    area        DECIMAL(6, 1) NULL,
    floor       INT           NULL,
    status      VARCHAR(16)   NOT NULL DEFAULT 'PREPARING',
    owner_name  VARCHAR(150)  NULL,
    owner_phone VARCHAR(40)   NULL,
    note        VARCHAR(500)  NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT fk_stay_units_category FOREIGN KEY (category_id) REFERENCES stay_categories (id),
    CONSTRAINT uq_stay_units_number UNIQUE (building, number)
);

--changeset behzod:41
-- Chorvoq Stay: agentlik xizmatiga qo'shilmoqchi bo'lgan xonadon mulkdorlari (sayt so'rovnomasidan yoki qo'lda).
-- status: NEW — yangi, CONTACTED — bog'lanildi, VIEWING — ko'rik belgilandi/o'tkazildi, AGREED — kelishildi (shartnoma),
-- CONNECTED — kvartira ulandi (stay_units), REJECTED — rad etildi / bekor.
CREATE TABLE stay_owners
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(150)  NOT NULL,
    phone             VARCHAR(40)   NULL,
    building          VARCHAR(20)   NULL,
    apartment         VARCHAR(20)   NULL,
    area              DECIMAL(6, 1) NULL,
    renovation        VARCHAR(100)  NULL,
    note              VARCHAR(1000) NULL,
    lang              VARCHAR(8)    NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'NEW',
    comment           VARCHAR(1000) NULL,
    request_id        BIGINT        NULL,
    unit_id           BIGINT        NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    status_changed_at DATETIME(6)   NOT NULL,
    updated_by        VARCHAR(100)  NULL,
    CONSTRAINT fk_stay_owners_unit FOREIGN KEY (unit_id) REFERENCES stay_units (id) ON DELETE SET NULL,
    INDEX idx_stay_owners_status (status, status_changed_at)
);
-- Oldin kelgan rezident so'rovnomalari reyestrga ko'chiriladi.
INSERT INTO stay_owners (name, phone, building, apartment, area, renovation, note, lang, status, request_id,
                         created_at, updated_at, status_changed_at)
SELECT COALESCE(NULLIF(name, ''), '—'), phone,
       LEFT(JSON_UNQUOTE(JSON_EXTRACT(payload, '$."Uy raqami"')), 20),
       LEFT(JSON_UNQUOTE(JSON_EXTRACT(payload, '$."Kvartira raqami"')), 20),
       CASE WHEN REPLACE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$."Maydon, m²"')), ',', '.') REGEXP '^[0-9]+(\.[0-9]+)?$'
            THEN CAST(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$."Maydon, m²"')), ',', '.') AS DECIMAL(6, 1)) END,
       LEFT(JSON_UNQUOTE(JSON_EXTRACT(payload, '$."Remont holati"')), 100),
       NULLIF(LEFT(JSON_UNQUOTE(JSON_EXTRACT(payload, '$."Izoh"')), 1000), '—'),
       lang, CASE WHEN status = 'DONE' THEN 'CONTACTED' ELSE 'NEW' END, id,
       created_at, COALESCE(handled_at, created_at), COALESCE(handled_at, created_at)
FROM stay_requests
WHERE kind LIKE 'Rezident%';

--changeset behzod:42
-- Mulkdorlar reyestrida remont holati kirillcha (HotelPulse interfeysi faqat kirillcha).
UPDATE stay_owners SET renovation = CASE renovation
    WHEN 'Remont bor, mebel va texnika yo''q' THEN 'Ремонт бор, мебел ва техника йўқ'
    WHEN 'Remont va mebel bor' THEN 'Ремонт ва мебел бор'
    WHEN 'Qisman remont' THEN 'Қисман ремонт'
    WHEN 'Remontsiz (qora suvoq)' THEN 'Ремонтсиз (қора сувоқ)'
    ELSE renovation END;
