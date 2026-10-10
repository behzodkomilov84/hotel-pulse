--liquibase formatted sql

--changeset behzod:24
-- Bo'limlar: har bir mehmonxonaning o'zida (Resepshn, Buxgalteriya, ...). Mehmonxona egasi / boshqaruv kompaniyasi
-- yaratadi va xodimlarni biriktiradi; bitta xodim bir nechta bo'limda bo'lishi mumkin.
CREATE TABLE departments
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    hotel_id   BIGINT      NOT NULL,
    name       VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_departments_hotel FOREIGN KEY (hotel_id) REFERENCES hotels (id) ON DELETE CASCADE,
    CONSTRAINT uq_departments_hotel_name UNIQUE (hotel_id, name)
);

CREATE TABLE user_departments
(
    user_id       BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, department_id),
    CONSTRAINT fk_user_departments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_departments_department FOREIGN KEY (department_id) REFERENCES departments (id) ON DELETE CASCADE
);

-- Topshiriq qaysi bo'limga berilgani (bo'lim o'chirilsa — topshiriqdagi nomi qoladi).
ALTER TABLE tasks
    ADD COLUMN department_id BIGINT NULL AFTER department,
    ADD CONSTRAINT fk_tasks_department FOREIGN KEY (department_id) REFERENCES departments (id) ON DELETE SET NULL;

-- Mavjud mehmonxonalarga tahlil tavsiyalaridagi bo'limlar.
INSERT INTO departments (hotel_id, name, created_at)
SELECT h.id, d.name, NOW(6)
FROM hotels h
         CROSS JOIN (SELECT 'Resepshn' AS name UNION ALL SELECT 'Buxgalteriya' UNION ALL SELECT 'Rahbariyat') d;
