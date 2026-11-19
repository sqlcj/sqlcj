-- The views, created before the column the next migration adds.

CREATE VIEW author_rows AS
SELECT *
FROM authors;

CREATE VIEW active_authors (ident, author_name) AS
SELECT id,
       name
FROM authors
WHERE NOT retired;

CREATE VIEW published_books AS
SELECT id,
       title
FROM books
WHERE published;

CREATE VIEW recent_books AS
SELECT id,
       title
FROM books;

CREATE MATERIALIZED VIEW author_book_counts AS
SELECT a.id        AS author_id,
       count(b.id) AS book_count
FROM authors a
         LEFT JOIN books b ON b.author_id = a.id
GROUP BY a.id
    WITH NO DATA;
