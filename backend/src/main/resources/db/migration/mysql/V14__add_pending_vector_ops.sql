-- v8.6.1(9.1): 向量操作补偿表——删除最终失败落表，重放任务按退避重放
CREATE TABLE pending_vector_ops (
  id VARCHAR(32) NOT NULL PRIMARY KEY,
  op_type VARCHAR(16) NOT NULL,
  collection VARCHAR(64) NOT NULL,
  expr TEXT NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error TEXT,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  next_attempt_at DATETIME(6) NULL,
  created_at DATETIME(6) NULL,
  updated_at DATETIME(6) NULL
);

CREATE INDEX idx_pending_vector_ops_status ON pending_vector_ops(status, next_attempt_at);
