-- A column added after the views, a replaced and a recreated view, a view over
-- a view, and the refresh the materialized view needs before it is read.

ALTER TABLE authors ADD COLUMN pen_name text;

CREATE OR REPLACE VIEW published_books AS
SELECT id,
       title,
       author_id
FROM books
WHERE published;

DROP VIEW recent_books;

CREATE VIEW recent_books AS
SELECT id,
       author_id
FROM books;

CREATE VIEW active_author_names AS
SELECT author_name
FROM active_authors;

REFRESH MATERIALIZED VIEW author_book_counts;
