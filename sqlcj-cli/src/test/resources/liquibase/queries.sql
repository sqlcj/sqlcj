-- The named SQL of the Liquibase fixture, read against either form of its schema.

-- name: CreateAuthor :one
INSERT INTO authors (full_name)
VALUES (:fullName)
RETURNING *;

-- name: CreateBook :one
INSERT INTO books (author_id, title, format, isbn)
VALUES (:authorId, :title, :format, :isbn)
RETURNING *;

-- name: ListBooksByAuthor :many
SELECT *
FROM books
WHERE author_id = :authorId
ORDER BY title;

-- name: ListTitlesByFormat :many
SELECT b.title, a.full_name
FROM books b
         JOIN authors a ON a.id = b.author_id
WHERE b.format = :format
ORDER BY b.title;
