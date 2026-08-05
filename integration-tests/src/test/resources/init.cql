CREATE KEYSPACE IF NOT EXISTS orm_test
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE orm_test;

CREATE TABLE IF NOT EXISTS person (
id uuid PRIMARY KEY,
name text,
address_id uuid
);

CREATE TABLE IF NOT EXISTS address (
id uuid PRIMARY KEY,
street text,
housenumber text
);

CREATE TABLE IF NOT EXISTS book (
id uuid PRIMARY KEY,
title text,
active boolean
);

CREATE TABLE IF NOT EXISTS author (
id uuid PRIMARY KEY,
name text
);

CREATE TABLE IF NOT EXISTS event (
tenant text,
device_id uuid,
occurred_at timestamp,
event_id uuid,
event_day date,
time_of_day time,
amount decimal,
hit_count varint,
payload blob,
PRIMARY KEY ((tenant, device_id), occurred_at, event_id)
);

CREATE TABLE IF NOT EXISTS profile (
id uuid PRIMARY KEY,
status text,
tier int,
tags list<text>,
scores set<int>,
attributes map<text, text>,
settings text
);

CREATE TABLE IF NOT EXISTS probe (
id uuid PRIMARY KEY,
raw blob
);

CREATE TABLE IF NOT EXISTS temporal_probe (
id uuid PRIMARY KEY,
created_at timestamp
);

CREATE TABLE IF NOT EXISTS audited (
id uuid PRIMARY KEY,
payload text,
created_by text
);

CREATE INDEX IF NOT EXISTS person_name_idx ON person (name);
CREATE INDEX IF NOT EXISTS book_title_idx ON book (title);