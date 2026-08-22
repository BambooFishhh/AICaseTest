-- v6.7: 任务埋点关联 agent_task（task_id/attempt）
ALTER TABLE task_telemetry ADD COLUMN task_id VARCHAR(64) NULL;
ALTER TABLE task_telemetry ADD COLUMN attempt INT NULL;
