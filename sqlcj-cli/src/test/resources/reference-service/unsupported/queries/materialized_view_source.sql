-- Outside the supported set: sqlcj models no materialized view.
-- name: ListProjectTaskCounts :many
SELECT project_id,
       task_count
FROM project_task_counts;
