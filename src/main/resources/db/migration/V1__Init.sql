create schema if not exists bankAggregator;

create table bankAggregator.transaction (
      id                UUID PRIMARY KEY,
      created_at        BIGINT NOT NULL
)