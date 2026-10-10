--liquibase formatted sql

--changeset behzod:22
-- Topshiriqlar: tahlil tavsiyalari (yoki qo'lda) asosida mehmonxona xodimiga beriladi.
-- Holat: NEW → IN_PROGRESS → REVIEW (xodim "bajarildi" dedi) → DONE (tasdiqlandi) yoki RETURNED (qaytarildi).
-- last_reminded_on — muddat eslatmasi shu kuni yuborilgan (kuniga bir martadan ortiq emas).
CREATE TABLE tasks
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id         BIGINT       NOT NULL,
    title            VARCHAR(255) NOT NULL,
    description      TEXT         NULL,
    department       VARCHAR(64)  NULL,
    booking_number   VARCHAR(64)  NULL,
    source           VARCHAR(32)  NOT NULL,
    assigned_by_id   BIGINT       NULL,
    assignee_id      BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    due_date         DATE         NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    completed_at     DATETIME(6)  NULL,
    reviewed_at      DATETIME(6)  NULL,
    last_reminded_on DATE         NULL,
    CONSTRAINT fk_tasks_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT fk_tasks_assigned_by FOREIGN KEY (assigned_by_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_tasks_assignee FOREIGN KEY (assignee_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_tasks_hotel_status (hotel_id, status),
    INDEX idx_tasks_assignee_status (assignee_id, status),
    INDEX idx_tasks_status_due (status, due_date)
);

-- Topshiriq tarixi: kim, qachon, nima qildi (berdi, boshladi, bajardi, tasdiqladi, qaytardi, izoh, eslatma).
CREATE TABLE task_events
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id    BIGINT      NOT NULL,
    user_id    BIGINT      NULL,
    action     VARCHAR(16) NOT NULL,
    channel    VARCHAR(8)  NOT NULL,
    comment    TEXT        NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_task_events_task FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_task_events_task (task_id, created_at)
);

--changeset behzod:23
-- Topshiriqqa ilova qilingan bronlar ro'yxati (tahlil tavsiyasidagi "ketgan mehmonlar", "vyselenie qilinmagan" va h.k.)
-- — topshiriq berilgan paytdagi holat. done — xodim shu qatorni bajarib, belgilagan.
CREATE TABLE task_items
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id        BIGINT        NOT NULL,
    position       INT           NOT NULL,
    booking_number VARCHAR(64)   NULL,
    guest_name     VARCHAR(255)  NULL,
    source         VARCHAR(255)  NULL,
    category       VARCHAR(32)   NULL,
    arrival        DATE          NULL,
    departure      DATE          NULL,
    total          DECIMAL(19, 2) NOT NULL,
    paid           DECIMAL(19, 2) NOT NULL,
    debt           DECIMAL(19, 2) NOT NULL,
    age_days       INT           NOT NULL,
    done           BOOLEAN       NOT NULL DEFAULT FALSE,
    done_at        DATETIME(6)   NULL,
    CONSTRAINT fk_task_items_task FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE,
    INDEX idx_task_items_task (task_id, position)
);
