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
