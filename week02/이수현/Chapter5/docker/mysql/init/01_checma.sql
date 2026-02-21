USE chapter5;

DROP TABLE IF EXISTS event_apply;
DROP TABLE IF EXISTS event;

CREATE TABLE event(
  event_id BIGINT PRIMARY KEY,
  title VARCHAR(100) NOT NULL,
  capacity INT NOT NULL,
  remaining INT NOT NULL,
  starts_at DATETIME NULL,
  ends_at DATETIME NULL,
  CHECK (capacity >= 0),
  CHECK (remaining >= 0)
)ENGINE=InnoDB;

CREATE TABLE event_apply (
  apply_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_event_user (event_id, user_id), -- 중복 신청 방지
  KEY idx_event_created (event_id, created_at), -- 조회/정렬용
  CONSTRAINT fk_event_apply_event
    FOREIGN KEY (event_id) REFERENCES event(event_id)
)ENGINE=InnoDB;