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

CREATE INDEX IF NOT EXISTS person_name_idx ON person (name);
CREATE INDEX IF NOT EXISTS book_title_idx ON book (title);