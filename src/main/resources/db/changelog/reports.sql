--liquibase formatted sql

--changeset behzod:25
-- Hisobotlar uchun: yashashning xona turi, xonasi va agent komissiyasi (mehmonxona valyutasida).
ALTER TABLE bookings
    ADD COLUMN room_type_id     VARCHAR(32)    NULL,
    ADD COLUMN room_id          VARCHAR(32)    NULL,
    ADD COLUMN agent_commission DECIMAL(15, 2) NULL;

-- Mavjud PMS yashashlari — xom Exely bron JSON'idan (roomStays). Komissiya bron bo'yicha beriladi:
-- yashashga bron summasidagi ulushi bo'yicha, bron valyutasidan mehmonxonanikiga o'tkazilgan narx nisbati bilan.
UPDATE bookings b
    JOIN (SELECT r.hotel_id,
                 CONCAT(_utf8mb4'pms:' COLLATE utf8mb4_0900_ai_ci, r.booking_number, _utf8mb4'#' COLLATE utf8mb4_0900_ai_ci, rs.rs_id COLLATE utf8mb4_0900_ai_ci) AS ext_id,
                 rs.room_id,
                 rs.room_type_id,
                 rs.amount,
                 CAST(JSON_EXTRACT(r.payload, '$.agentCommission.amount.amount') AS DECIMAL(15, 2)) AS commission,
                 (SELECT SUM(t.amount)
                  FROM JSON_TABLE(r.payload, '$.roomStays[*]' COLUMNS (amount DECIMAL(15, 2) PATH '$.totalPrice.amount')) t) AS booking_total
          FROM exely_raw r,
               JSON_TABLE(r.payload, '$.roomStays[*]' COLUMNS (
                   rs_id VARCHAR(64) CHARACTER SET utf8mb4 PATH '$.id',
                   room_id VARCHAR(32) CHARACTER SET utf8mb4 PATH '$.roomId',
                   room_type_id VARCHAR(32) CHARACTER SET utf8mb4 PATH '$.roomTypeId',
                   amount DECIMAL(15, 2) PATH '$.totalPrice.amount')) rs
          WHERE r.kind = 'booking') x ON x.hotel_id = b.hotel_id AND x.ext_id = b.external_id AND b.origin = 'EXELY_PMS'
SET b.room_id          = x.room_id,
    b.room_type_id     = x.room_type_id,
    b.agent_commission = CASE
                             WHEN x.commission IS NULL OR x.commission = 0 OR x.booking_total IS NULL OR x.booking_total = 0 THEN NULL
                             ELSE ROUND(x.commission * b.total_amount / x.booking_total, 2) END;

--changeset behzod:26
-- Foydalanuvchining hisobotlar ekrani: bloklar tarkibi va tartibi (vergul bilan; bo'sh — standart).
CREATE TABLE user_report_layouts
(
    user_id    BIGINT        NOT NULL PRIMARY KEY,
    block_keys VARCHAR(2000) NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    CONSTRAINT fk_user_report_layouts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

--changeset behzod:27
-- USALI xarajatlari — oyma-oy, modda bo'yicha. source: MANUAL (saytda) yoki keyinchalik 1C.
CREATE TABLE usali_expenses
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id   BIGINT         NOT NULL,
    month      DATE           NOT NULL,
    line       VARCHAR(48)    NOT NULL,
    amount     DECIMAL(17, 2) NOT NULL,
    source     VARCHAR(8)     NOT NULL DEFAULT 'MANUAL',
    updated_by BIGINT         NULL,
    updated_at DATETIME(6)    NOT NULL,
    CONSTRAINT fk_usali_expenses_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT fk_usali_expenses_user FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uq_usali_expenses UNIQUE (hotel_id, month, line)
);

--changeset behzod:28
-- Talab dinamikasi ("Оценка интенсивности спроса"): har kuni kelgusi kunlar uchun sotilgan xonalar soni.
CREATE TABLE otb_snapshots
(
    hotel_id      BIGINT NOT NULL,
    snapshot_date DATE   NOT NULL,
    stay_date     DATE   NOT NULL,
    rooms_sold    INT    NOT NULL,
    PRIMARY KEY (hotel_id, snapshot_date, stay_date),
    CONSTRAINT fk_otb_snapshots_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE
);

--changeset behzod:29
-- Interfeys o'zbek kirill yozuviga o'tdi: standart bo'limlar va "noma'lum" manba nomi ham kirillda
-- (tahlil tavsiyalaridagi bo'lim nomi bo'yicha xodim tanlash ishlashi uchun).
UPDATE departments SET name = 'Ресепшн' WHERE name = 'Resepshn';
UPDATE departments SET name = 'Бухгалтерия' WHERE name = 'Buxgalteriya';
UPDATE departments SET name = 'Раҳбарият' WHERE name = 'Rahbariyat';
UPDATE tasks SET department = 'Ресепшн' WHERE department = 'Resepshn';
UPDATE tasks SET department = 'Бухгалтерия' WHERE department = 'Buxgalteriya';
UPDATE tasks SET department = 'Раҳбарият' WHERE department = 'Rahbariyat';
UPDATE bookings SET source = 'Номаълум' WHERE source = 'Noma''lum';
