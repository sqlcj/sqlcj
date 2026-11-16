-- Billing service, first migration: the customers it invoices.
--
-- Every name is written unqualified, as a Flyway history whose defaultSchema is
-- billing writes it, so the schema this history builds is decided by the
-- search_path the migration tool sets and never by these files.

CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public;

CREATE TABLE customers
(
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email      citext      NOT NULL UNIQUE,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
