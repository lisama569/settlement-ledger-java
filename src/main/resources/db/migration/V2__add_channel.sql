-- V2: 结构演进 —— 新增渠道字段
-- 历史行以确定性默认值 'UNKNOWN' 回填，而非留 NULL 导致语义歧义
ALTER TABLE settlement_instructions ADD COLUMN channel VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';

-- 事件负载版本仍由 payload_version 行内区分：
--   payload_version=1 的历史 SUBMITTED 事件缺少 channel，重放时按 'UNKNOWN' 确定性补齐
