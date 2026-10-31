-- name: CreateAuthor :one
INSERT INTO authors (name, bio)
VALUES ($1, $2)
RETURNING *;

-- name: CountAuthors :one
SELECT COUNT(*) AS total
FROM authors;

-- name: ListAuthors :many
SELECT *
FROM authors
ORDER BY id;
