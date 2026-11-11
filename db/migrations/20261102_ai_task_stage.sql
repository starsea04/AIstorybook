USE storybook;

-- Existing installations created before the async task stage field was added.
SET @stage_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'ai_task' AND column_name = 'stage'
);
SET @stage_sql := IF(@stage_exists = 0,
  'ALTER TABLE ai_task ADD COLUMN stage VARCHAR(40) NULL AFTER status',
  'SELECT 1');
PREPARE stage_stmt FROM @stage_sql;
EXECUTE stage_stmt;
DEALLOCATE PREPARE stage_stmt;

SET @stage_index_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ai_task' AND index_name = 'idx_task_stage'
);
SET @stage_index_sql := IF(@stage_index_exists = 0,
  'ALTER TABLE ai_task ADD INDEX idx_task_stage (stage)',
  'SELECT 1');
PREPARE stage_index_stmt FROM @stage_index_sql;
EXECUTE stage_index_stmt;
DEALLOCATE PREPARE stage_index_stmt;
