-- One query per row and form of the function catalogue, each written without a
-- cast, so PostgreSQL 16 states the type and the nullness sqlcj analyzed, and
-- one query per argument position that types an uncast placeholder, so
-- PostgreSQL 16 infers the type sqlcj analyzed for the bare marker it binds
-- there.

-- name: SelectNow :one
SELECT now() AS read_at;

-- name: SelectGeneratedId :one
SELECT gen_random_uuid() AS generated_id;

-- name: NextEventNumber :one
SELECT nextval('event_number_seq') AS next_number;

-- name: TryLockEvent :one
SELECT pg_try_advisory_xact_lock($1::bigint) AS locked;

-- name: TryLockEventPair :one
SELECT pg_try_advisory_xact_lock($1::integer, $2::integer) AS locked;

-- name: TryLockEventKey :one
SELECT pg_try_advisory_xact_lock($1) AS locked;

-- name: TryLockEventKeyPair :one
SELECT pg_try_advisory_xact_lock($1, $2) AS locked;

-- name: NumberEvents :many
SELECT row_number() OVER (ORDER BY id) AS rn FROM events;

-- name: RankEvents :many
SELECT rank() OVER (ORDER BY visits) AS ranked,
       dense_rank() OVER (ORDER BY visits) AS densely_ranked
FROM events
ORDER BY id;

-- name: ListEventNames :many
SELECT lower(label)             AS lowered,
       upper(label)             AS uppered,
       lower(nickname)          AS lowered_nickname,
       trim(label)              AS trimmed,
       trim(BOTH ' ' FROM code) AS trimmed_code
FROM events
ORDER BY id;

-- name: FindEventByLabel :many
SELECT id
FROM events
WHERE lower(label) = lower($1)
ORDER BY id;

-- name: TrimEventLabels :many
SELECT trim(BOTH FROM label) AS trimmed,
       trim(BOTH FROM $1)    AS trimmed_input
FROM events
ORDER BY id;

-- name: ListEventSpans :many
SELECT id,
       date_trunc($1, span) AS truncated_span
FROM events
ORDER BY id;

-- name: ListEventOffsets :many
SELECT id,
       lag(score, $1) OVER (ORDER BY id) AS previous_score
FROM events
ORDER BY id;

-- name: ListEventDays :many
SELECT date_trunc('day', at_local)         AS local_day,
       date_trunc('day', at_zoned)         AS zoned_day,
       date_trunc('day', at_day)           AS calendar_day,
       date_trunc('hour', span)            AS truncated_span,
       date_trunc('day', at_local, 'UTC')  AS zoned_local_day
FROM events
ORDER BY id;

-- name: ListEventTotals :many
SELECT coalesce(score, 0)      AS score_or_zero,
       coalesce(score, total)  AS score_or_total,
       greatest(score, visits) AS highest,
       least(score, visits)    AS lowest
FROM events
ORDER BY id;

-- name: ListEventTrends :many
SELECT lag(score) OVER (ORDER BY id)       AS previous_score,
       lead(score) OVER (ORDER BY id)      AS next_score,
       lag(score, 1, 0) OVER (ORDER BY id) AS previous_or_zero,
       first_value(score) OVER (PARTITION BY id ORDER BY id) AS first_score,
       last_value(score) OVER (ORDER BY id) AS last_score
FROM events
ORDER BY id;
