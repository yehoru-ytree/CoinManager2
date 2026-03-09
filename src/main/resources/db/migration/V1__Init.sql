create schema if not exists bankAggregator;

create table if not exists bankAggregator.transaction (
      id                text PRIMARY KEY,
      created_at        BIGINT NOT NULL,
      raw JSONB
);

create table if not exists bankAggregator.transaction_status (
      transaction_id                text PRIMARY KEY,
      status            text
)