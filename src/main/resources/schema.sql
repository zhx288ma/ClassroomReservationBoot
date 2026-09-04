CREATE TABLE IF NOT EXISTS tb_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  password VARCHAR(160) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  role VARCHAR(32) NOT NULL DEFAULT 'USER',
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_phone (phone)
);

CREATE TABLE IF NOT EXISTS tb_classroom (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  building_name VARCHAR(64) NOT NULL,
  room_number VARCHAR(64) NOT NULL,
  capacity INT NOT NULL,
  room_type VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  equipment VARCHAR(255),
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_building_room (building_name, room_number),
  KEY idx_room_status_capacity (status, capacity)
);

CREATE TABLE IF NOT EXISTS tb_room_slot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  reserve_date DATE NOT NULL,
  time_slot VARCHAR(32) NOT NULL,
  total_capacity INT NOT NULL DEFAULT 1,
  available_capacity INT NOT NULL DEFAULT 1,
  status TINYINT NOT NULL DEFAULT 1,
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_room_slot (room_id, reserve_date, time_slot),
  KEY idx_slot_date (reserve_date, time_slot)
);

CREATE TABLE IF NOT EXISTS tb_reserve_order (
  id BIGINT PRIMARY KEY,
  room_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  reserve_date DATE NOT NULL,
  time_slot VARCHAR(32) NOT NULL,
  active_key VARCHAR(128),
  status TINYINT NOT NULL DEFAULT 1,
  sign_code VARCHAR(16),
  remark VARCHAR(255),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_active_key (active_key),
  KEY idx_user_time (user_id, reserve_date, time_slot),
  KEY idx_room_time (room_id, reserve_date, time_slot),
  KEY idx_status_time (status, create_time)
);

CREATE TABLE IF NOT EXISTS tb_reserve_waitlist (
  id BIGINT PRIMARY KEY,
  room_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  reserve_date DATE NOT NULL,
  time_slot VARCHAR(32) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_waiting_user_slot (user_id, room_id, reserve_date, time_slot, status),
  KEY idx_wait_slot (room_id, reserve_date, time_slot, status, create_time)
);

CREATE TABLE IF NOT EXISTS tb_notification (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  content VARCHAR(512) NOT NULL,
  read_status TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_notify_user (user_id, read_status, create_time)
);

CREATE TABLE IF NOT EXISTS tb_notification_outbox (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  content VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error VARCHAR(512),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_outbox_due (status, next_retry_time),
  KEY idx_outbox_user (user_id, create_time)
);

CREATE TABLE IF NOT EXISTS tb_audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(64) NOT NULL,
  user_id BIGINT,
  role VARCHAR(32),
  http_method VARCHAR(16) NOT NULL,
  uri VARCHAR(255) NOT NULL,
  http_status INT NOT NULL,
  success TINYINT NOT NULL DEFAULT 1,
  latency_ms BIGINT NOT NULL,
  client_ip VARCHAR(64),
  error_msg VARCHAR(512),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_audit_trace (trace_id),
  KEY idx_audit_user_time (user_id, create_time),
  KEY idx_audit_success_time (success, create_time)
);

CREATE TABLE IF NOT EXISTS tb_feedback_ticket (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  content VARCHAR(1000) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  admin_reply VARCHAR(1000),
  replied_by BIGINT,
  replied_time DATETIME,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_feedback_user_status (user_id, status, create_time),
  KEY idx_feedback_status_time (status, create_time)
);
