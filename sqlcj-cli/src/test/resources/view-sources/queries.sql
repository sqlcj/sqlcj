-- The named SQL of the view fixture's Catalog group.

-- name: CreateAuthor :one
INSERT INTO authors (name, pen_name)
VALUES (:name, :penName)
RETURNING *;

-- name: CreateBook :one
INSERT INTO books (author_id, title, published)
VALUES (:authorId, :title, :published)
RETURNING *;

-- name: RetireAuthor :exec
UPDATE authors
SET retired = TRUE
WHERE id = :id;

-- name: ListAuthorRows :many
SELECT *
FROM author_rows
ORDER BY id;

-- name: ListActiveAuthors :many
SELECT ident,
       author_name
FROM active_authors
ORDER BY ident;

-- name: ListActiveAuthorNames :many
SELECT author_name
FROM active_author_names
ORDER BY author_name;

-- name: ListPublishedBooks :many
SELECT b.id,
       b.title,
       b.author_id,
       a.author_name
FROM published_books b
         JOIN active_authors a ON a.ident = b.author_id
ORDER BY b.id;

-- name: ListRecentBooks :many
SELECT id,
       author_id
FROM recent_books
ORDER BY id;

-- name: CountAuthorBooks :optional
SELECT book_count
FROM author_book_counts
WHERE author_id = :authorId;
