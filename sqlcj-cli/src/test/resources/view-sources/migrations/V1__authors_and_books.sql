-- The tables the views of this fixture read.

CREATE TABLE authors
(
    id      bigint GENERATED ALWAYS AS IDENTITY NOT NULL PRIMARY KEY,
    name    text    NOT NULL,
    retired boolean NOT NULL DEFAULT false
);

CREATE TABLE books
(
    id        bigint GENERATED ALWAYS AS IDENTITY NOT NULL PRIMARY KEY,
    author_id bigint  NOT NULL REFERENCES authors (id),
    title     text    NOT NULL,
    published boolean NOT NULL DEFAULT true
);
