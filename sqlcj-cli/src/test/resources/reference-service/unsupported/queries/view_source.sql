-- Outside the supported set: sqlcj models no view.
-- name: ListUnfinishedTasks :many
SELECT id,
       title
FROM unfinished_tasks
ORDER BY id;
