# --- !Ups

CREATE TABLE links (
  code          VARCHAR(16)  NOT NULL PRIMARY KEY,
  original_url  TEXT         NOT NULL,
  created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE clicks (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(16)  NOT NULL,
  clicked_at  TIMESTAMP    NOT NULL
);

# --- !Downs

DROP TABLE clicks;
DROP TABLE links;
