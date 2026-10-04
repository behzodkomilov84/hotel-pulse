--liquibase formatted sql

--changeset behzod:9
-- Foydalanuvchining Telegram chat'i (bitta foydalanuvchi — bitta chat).
-- telegram_daily_report — har kuni ertalab kechagi kun hisoboti yuborilsinmi.
ALTER TABLE users
    ADD COLUMN telegram_chat_id      BIGINT      NULL,
    ADD COLUMN telegram_daily_report BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN telegram_linked_at    DATETIME(6) NULL,
    ADD CONSTRAINT uq_users_telegram_chat UNIQUE (telegram_chat_id);

--changeset behzod:10
-- Saytda yaratiladigan bir martalik ulash havolasi (t.me/<bot>?start=<token>).
CREATE TABLE telegram_link_tokens
(
    token      VARCHAR(64) PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_tg_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
