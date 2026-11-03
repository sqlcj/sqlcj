-- The queries of the null-checker fixture: every result shape a generated
-- repository has, over a table that holds every mapped type, scalar and
-- array, nullable and NOT NULL.

-- name: GetReading :one
SELECT *
FROM readings
WHERE id = $1;

-- name: FindReading :optional
SELECT *
FROM readings
WHERE id = $1;

-- name: ListReadings :many
SELECT *
FROM readings;

-- name: ListReadingSummaries :many
SELECT id,
       nn_text,
       null_text,
       nn_mood,
       null_mood,
       nn_interval,
       null_interval,
       nn_bytea,
       null_bytea,
       nn_jsonb,
       null_jsonb,
       nn_inet,
       null_inet,
       nn_text_array,
       null_text_array,
       nn_mood_array,
       null_mood_array,
       nn_interval_array,
       null_interval_array
FROM readings
ORDER BY id;

-- name: GetReadingWithSensor :one
SELECT r.*, s.*
FROM readings r
         JOIN sensors s ON s.id = r.sensor_id
WHERE r.id = $1;

-- name: FindReadingWithOwner :optional
SELECT r.*, o.*
FROM readings r
         LEFT JOIN sensors o ON o.id = r.owner_id
WHERE r.id = $1;

-- name: InsertSensor :one
INSERT INTO sensors (name, note)
VALUES ($1, $2)
RETURNING *;

-- name: UpdateSensorNote :one
UPDATE sensors
SET note = $2
WHERE id = $1
RETURNING id, name, note;

-- name: DeleteReading :exec
DELETE
FROM readings
WHERE id = $1;

-- name: InsertSensors :batchexec
INSERT INTO sensors (name, note)
VALUES ($1, $2);

-- One value query per NOT NULL column, each returning the value itself.

-- name: GetNnInteger :one
SELECT nn_integer
FROM readings
WHERE id = $1;

-- name: GetNnBigint :one
SELECT nn_bigint
FROM readings
WHERE id = $1;

-- name: GetNnSmallint :one
SELECT nn_smallint
FROM readings
WHERE id = $1;

-- name: GetNnBoolean :one
SELECT nn_boolean
FROM readings
WHERE id = $1;

-- name: GetNnVarchar :one
SELECT nn_varchar
FROM readings
WHERE id = $1;

-- name: GetNnText :one
SELECT nn_text
FROM readings
WHERE id = $1;

-- name: GetNnDate :one
SELECT nn_date
FROM readings
WHERE id = $1;

-- name: GetNnTime :one
SELECT nn_time
FROM readings
WHERE id = $1;

-- name: GetNnTimestamp :one
SELECT nn_timestamp
FROM readings
WHERE id = $1;

-- name: GetNnTimestamptz :one
SELECT nn_timestamptz
FROM readings
WHERE id = $1;

-- name: GetNnNumeric :one
SELECT nn_numeric
FROM readings
WHERE id = $1;

-- name: GetNnReal :one
SELECT nn_real
FROM readings
WHERE id = $1;

-- name: GetNnDouble :one
SELECT nn_double
FROM readings
WHERE id = $1;

-- name: GetNnUuid :one
SELECT nn_uuid
FROM readings
WHERE id = $1;

-- name: GetNnBytea :one
SELECT nn_bytea
FROM readings
WHERE id = $1;

-- name: GetNnJson :one
SELECT nn_json
FROM readings
WHERE id = $1;

-- name: GetNnJsonb :one
SELECT nn_jsonb
FROM readings
WHERE id = $1;

-- name: GetNnCitext :one
SELECT nn_citext
FROM readings
WHERE id = $1;

-- name: GetNnInet :one
SELECT nn_inet
FROM readings
WHERE id = $1;

-- name: GetNnCidr :one
SELECT nn_cidr
FROM readings
WHERE id = $1;

-- name: GetNnMacaddr :one
SELECT nn_macaddr
FROM readings
WHERE id = $1;

-- name: GetNnMacaddr8 :one
SELECT nn_macaddr8
FROM readings
WHERE id = $1;

-- name: GetNnInterval :one
SELECT nn_interval
FROM readings
WHERE id = $1;

-- name: GetNnMood :one
SELECT nn_mood
FROM readings
WHERE id = $1;

-- name: GetNnIntegerArray :one
SELECT nn_integer_array
FROM readings
WHERE id = $1;

-- name: GetNnBigintArray :one
SELECT nn_bigint_array
FROM readings
WHERE id = $1;

-- name: GetNnSmallintArray :one
SELECT nn_smallint_array
FROM readings
WHERE id = $1;

-- name: GetNnBooleanArray :one
SELECT nn_boolean_array
FROM readings
WHERE id = $1;

-- name: GetNnVarcharArray :one
SELECT nn_varchar_array
FROM readings
WHERE id = $1;

-- name: GetNnTextArray :one
SELECT nn_text_array
FROM readings
WHERE id = $1;

-- name: GetNnDateArray :one
SELECT nn_date_array
FROM readings
WHERE id = $1;

-- name: GetNnTimeArray :one
SELECT nn_time_array
FROM readings
WHERE id = $1;

-- name: GetNnTimestampArray :one
SELECT nn_timestamp_array
FROM readings
WHERE id = $1;

-- name: GetNnTimestamptzArray :one
SELECT nn_timestamptz_array
FROM readings
WHERE id = $1;

-- name: GetNnNumericArray :one
SELECT nn_numeric_array
FROM readings
WHERE id = $1;

-- name: GetNnRealArray :one
SELECT nn_real_array
FROM readings
WHERE id = $1;

-- name: GetNnDoubleArray :one
SELECT nn_double_array
FROM readings
WHERE id = $1;

-- name: GetNnUuidArray :one
SELECT nn_uuid_array
FROM readings
WHERE id = $1;

-- name: GetNnCitextArray :one
SELECT nn_citext_array
FROM readings
WHERE id = $1;

-- name: GetNnInetArray :one
SELECT nn_inet_array
FROM readings
WHERE id = $1;

-- name: GetNnCidrArray :one
SELECT nn_cidr_array
FROM readings
WHERE id = $1;

-- name: GetNnMacaddrArray :one
SELECT nn_macaddr_array
FROM readings
WHERE id = $1;

-- name: GetNnMacaddr8Array :one
SELECT nn_macaddr8_array
FROM readings
WHERE id = $1;

-- name: GetNnIntervalArray :one
SELECT nn_interval_array
FROM readings
WHERE id = $1;

-- name: GetNnMoodArray :one
SELECT nn_mood_array
FROM readings
WHERE id = $1;
