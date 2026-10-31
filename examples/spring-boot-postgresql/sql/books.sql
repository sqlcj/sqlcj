-- name: CreateBook :exec
INSERT INTO books (author_id, title)
VALUES ($1, $2);

-- name: CountBooks :one
SELECT COUNT(*) AS total
FROM books;

-- name: ListAuthorBooks :many
SELECT *
FROM books
WHERE author_id = $1
ORDER BY id;
