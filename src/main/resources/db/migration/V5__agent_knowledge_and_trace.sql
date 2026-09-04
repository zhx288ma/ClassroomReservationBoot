CREATE TABLE IF NOT EXISTS tb_agent_knowledge_document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(160) NOT NULL,
  category VARCHAR(64) NOT NULL DEFAULT 'POLICY',
  content MEDIUMTEXT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_by BIGINT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_agent_knowledge_status_category (status, category, update_time)
);

CREATE TABLE IF NOT EXISTS tb_agent_knowledge_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  content TEXT NOT NULL,
  keywords VARCHAR(512) NULL,
  embedding_json MEDIUMTEXT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_knowledge_chunk (document_id, chunk_index),
  KEY idx_agent_chunk_document (document_id)
);

CREATE TABLE IF NOT EXISTS tb_agent_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  session_id VARCHAR(96) NULL,
  intent VARCHAR(64) NOT NULL,
  mode VARCHAR(64) NOT NULL,
  input_summary VARCHAR(512) NOT NULL,
  tool_trace_json TEXT NULL,
  source_ids VARCHAR(256) NULL,
  success TINYINT NOT NULL DEFAULT 1,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  error_message VARCHAR(512) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_trace_id (trace_id),
  KEY idx_agent_trace_user_time (user_id, create_time),
  KEY idx_agent_trace_intent_time (intent, create_time)
);
