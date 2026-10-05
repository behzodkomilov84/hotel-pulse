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
