-- Outside the supported set: sqlcj models no function source.
-- name: ListTasksByIds :many
SELECT t.id,
       t.title
FROM unnest(:ids::bigint[]) AS ids(id)
         JOIN tasks t ON t.id = ids.id;
