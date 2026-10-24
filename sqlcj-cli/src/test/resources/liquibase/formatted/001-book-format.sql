--liquibase formatted sql

--changeset sqlcj:1
--comment: The formats a book is sold in.
CREATE TYPE book_format AS ENUM ('hardcover', 'paperback', 'ebook');
--rollback DROP TYPE book_format;
