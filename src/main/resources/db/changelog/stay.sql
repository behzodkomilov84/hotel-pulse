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
