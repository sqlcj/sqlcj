--liquibase formatted sql

--changeset sqlcj:4
ALTER TABLE books ADD COLUMN isbn VARCHAR(17);
ALTER TABLE books ADD CONSTRAINT uq_books_isbn UNIQUE (isbn);
ALTER TABLE authors RENAME COLUMN name TO full_name;
ALTER TABLE authors DROP COLUMN born;
--rollback ALTER TABLE authors ADD COLUMN born DATE;
--rollback ALTER TABLE authors RENAME COLUMN full_name TO name;
--rollback ALTER TABLE books DROP CONSTRAINT uq_books_isbn;
--rollback ALTER TABLE books DROP COLUMN isbn;
