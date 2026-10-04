--liquibase formatted sql

--changeset behzod:7
-- Exely Connect OAuth2 (client credentials): mehmonxona extranet'da
-- "Property settings > API connections"da yaratgan client_id/client_secret.
-- Eski exely_api_key ustuni client_secret sifatida qayta nomlanadi
-- (AES-GCM bilan shifrlangan holda qoladi).
ALTER TABLE hotels
    RENAME COLUMN exely_api_key TO exely_client_secret;
ALTER TABLE hotels
    ADD COLUMN exely_client_id VARCHAR(128) NULL AFTER exely_property_id;

--changeset behzod:8
-- Sinxronizatsiya holati. exely_continue_token — Read Reservation API'ning
-- keyingi so'rov uchun bergan tokeni: navbatdagi sinxronlash faqat shu
-- paytdan keyin o'zgargan bronlarni oladi.
ALTER TABLE hotels
    ADD COLUMN exely_continue_token   VARCHAR(1024) NULL,
    ADD COLUMN exely_last_sync_at     DATETIME(6)   NULL,
    ADD COLUMN exely_last_sync_ok     BOOLEAN       NULL,
    ADD COLUMN exely_last_sync_message VARCHAR(500)  NULL;
