USE storybook;

CREATE TABLE IF NOT EXISTS sensitive_word_hit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor_id BIGINT NULL,
  actor_name VARCHAR(64) NOT NULL DEFAULT '',
  context_key VARCHAR(40) NOT NULL,
  target_id BIGINT NULL,
  matched_word VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  INDEX idx_word_hit_time (created_at),
  INDEX idx_word_hit_context (context_key),
  INDEX idx_word_hit_actor (actor_id),
  INDEX idx_word_hit_word (matched_word)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
