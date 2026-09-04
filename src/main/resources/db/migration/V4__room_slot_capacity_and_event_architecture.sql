ALTER TABLE tb_room_slot ADD COLUMN reserved_count INT NOT NULL DEFAULT 0;
ALTER TABLE tb_room_slot ADD COLUMN waitlist_count INT NOT NULL DEFAULT 0;
ALTER TABLE tb_room_slot ADD COLUMN open_type VARCHAR(32) NOT NULL DEFAULT 'SELF_STUDY';
ALTER TABLE tb_room_slot ADD COLUMN created_by BIGINT NULL;

UPDATE tb_room_slot
SET reserved_count = GREATEST(total_capacity - available_capacity, 0);

UPDATE tb_room_slot
SET total_capacity = (SELECT c.capacity FROM tb_classroom c WHERE c.id = tb_room_slot.room_id),
    available_capacity = GREATEST((SELECT c.capacity FROM tb_classroom c WHERE c.id = tb_room_slot.room_id) - reserved_count, 0)
WHERE total_capacity = 1
  AND EXISTS (
    SELECT 1 FROM tb_classroom c
    WHERE c.id = tb_room_slot.room_id AND c.capacity > 1
  );

CREATE INDEX idx_room_slot_status_time ON tb_room_slot(status, reserve_date, time_slot);

ALTER TABLE tb_reserve_order ADD COLUMN room_slot_id BIGINT NULL;
ALTER TABLE tb_reserve_order ADD COLUMN cancelled_at DATETIME NULL;
ALTER TABLE tb_reserve_order ADD COLUMN checkin_deadline DATETIME NULL;
CREATE INDEX idx_reserve_room_slot ON tb_reserve_order(room_slot_id, status);

ALTER TABLE tb_reserve_waitlist ADD COLUMN room_slot_id BIGINT NULL;
ALTER TABLE tb_reserve_waitlist ADD COLUMN priority_score DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE tb_reserve_waitlist ADD COLUMN credit_score_snapshot INT NULL;
ALTER TABLE tb_reserve_waitlist ADD COLUMN violation_count_snapshot INT NULL;
ALTER TABLE tb_reserve_waitlist ADD COLUMN rank_no INT NULL;
ALTER TABLE tb_reserve_waitlist ADD COLUMN promoted_at DATETIME NULL;
ALTER TABLE tb_reserve_waitlist ADD COLUMN expire_at DATETIME NULL;
CREATE INDEX idx_waitlist_slot_priority ON tb_reserve_waitlist(room_slot_id, status, priority_score, create_time);

ALTER TABLE tb_notification ADD COLUMN notification_type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM';
ALTER TABLE tb_notification ADD COLUMN business_id BIGINT NULL;
ALTER TABLE tb_notification ADD COLUMN read_time DATETIME NULL;

CREATE TABLE IF NOT EXISTS tb_teacher_booking (
  id BIGINT PRIMARY KEY,
  teacher_id BIGINT NOT NULL,
  room_id BIGINT NOT NULL,
  room_slot_id BIGINT NULL,
  reserve_date DATE NOT NULL,
  time_slot VARCHAR(32) NOT NULL,
  purpose VARCHAR(255) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  approver_id BIGINT NULL,
  approve_remark VARCHAR(255),
  approved_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_teacher_booking_status (status, reserve_date, time_slot),
  KEY idx_teacher_booking_teacher (teacher_id, create_time)
);

CREATE TABLE IF NOT EXISTS tb_checkin (
  id BIGINT PRIMARY KEY,
  reservation_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  room_slot_id BIGINT NULL,
  checkin_type VARCHAR(32) NOT NULL DEFAULT 'CODE',
  checkin_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  client_ip VARCHAR(64),
  device_info VARCHAR(128),
  UNIQUE KEY uk_checkin_reservation (reservation_id),
  KEY idx_checkin_user_time (user_id, checkin_time)
);

CREATE TABLE IF NOT EXISTS tb_credit_account (
  user_id BIGINT PRIMARY KEY,
  credit_score INT NOT NULL DEFAULT 100,
  violation_count INT NOT NULL DEFAULT 0,
  last_change_time DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_credit_record (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  reservation_id BIGINT NULL,
  change_score INT NOT NULL,
  before_score INT NOT NULL,
  after_score INT NOT NULL,
  reason VARCHAR(64) NOT NULL,
  remark VARCHAR(255),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_credit_user_time (user_id, create_time)
);

CREATE TABLE IF NOT EXISTS tb_event_outbox (
  id BIGINT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  target_type VARCHAR(32) NOT NULL DEFAULT 'BOTH',
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  user_id BIGINT NULL,
  room_id BIGINT NULL,
  room_slot_id BIGINT NULL,
  payload TEXT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error VARCHAR(512),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_event_id (event_id),
  KEY idx_event_outbox_due (status, next_retry_time),
  KEY idx_event_type_time (event_type, create_time)
);

CREATE TABLE IF NOT EXISTS tb_event_statistics (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  stat_date DATE NOT NULL,
  stat_type VARCHAR(64) NOT NULL,
  room_id BIGINT NULL,
  room_slot_id BIGINT NULL,
  stat_key VARCHAR(128) NOT NULL,
  stat_value DECIMAL(18,4) NOT NULL DEFAULT 0,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_event_stat (stat_date, stat_type, stat_key),
  KEY idx_event_stat_room (room_id, room_slot_id)
);

CREATE TABLE IF NOT EXISTS tb_room_equipment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  equipment_name VARCHAR(64) NOT NULL,
  equipment_status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_room_equipment (room_id, equipment_name),
  KEY idx_equipment_name (equipment_name)
);

CREATE TABLE IF NOT EXISTS tb_room_tag (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  tag_name VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_room_tag (room_id, tag_name),
  KEY idx_tag_name (tag_name)
);
