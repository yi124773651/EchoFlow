-- V3: Add optimistic lock version columns to aggregate root tables

ALTER TABLE task ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE execution ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
