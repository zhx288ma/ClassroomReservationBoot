ALTER TABLE tb_agent_knowledge_document
  ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'TEXT' AFTER category,
  ADD COLUMN source_file_name VARCHAR(255) NULL AFTER source_type,
  ADD COLUMN source_file_path VARCHAR(512) NULL AFTER source_file_name,
  ADD COLUMN content_hash VARCHAR(64) NULL AFTER content,
  ADD COLUMN index_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' AFTER status,
  ADD COLUMN chunk_count INT NOT NULL DEFAULT 0 AFTER index_status,
  ADD COLUMN vector_count INT NOT NULL DEFAULT 0 AFTER chunk_count,
  ADD COLUMN last_indexed_at DATETIME NULL AFTER vector_count,
  ADD COLUMN last_index_error VARCHAR(512) NULL AFTER last_indexed_at;

CREATE INDEX idx_agent_knowledge_index_status ON tb_agent_knowledge_document(index_status, update_time);
