-- Cookbook fixture: a small newsletter publisher, its subscribers, and the
-- views of its articles. The cookbook's recipes read and write these tables.

CREATE TYPE article_status AS ENUM ('draft', 'published', 'archived');

CREATE TABLE articles
(
    id           bigint GENERATED ALWAYS AS IDENTITY NOT NULL PRIMARY KEY,
    slug         text                                NOT NULL UNIQUE,
    title        text                                NOT NULL,
    status       article_status                      NOT NULL,
    published_at timestamptz                         NOT NULL
);

CREATE TABLE subscribers
(
    id    bigint GENERATED ALWAYS AS IDENTITY NOT NULL PRIMARY KEY,
    email text                                NOT NULL UNIQUE,
    name  text                                NOT NULL
);

CREATE TABLE article_views
(
    article_id bigint      NOT NULL REFERENCES articles (id),
    viewed_at  timestamptz NOT NULL
);
