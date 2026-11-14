-- The queries of the shadowed-names fixture: one query of every result shape
-- a generated repository has over the table whose enum types are named like
-- the JDK, runtime, and JSpecify types generated code writes.

-- name: InsertMeasurement :one
INSERT INTO measurements (label, note, total, balance, taken_on, closed_on,
                          presence, magnitude, day_kind, count_kind, list_kind,
                          maybe_kind, mark_kind, text_kind, spare_presence)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)
RETURNING *;

-- name: FindMeasurement :optional
SELECT *
FROM measurements
WHERE id = $1;

-- name: ListMeasurements :many
SELECT *
FROM measurements
ORDER BY id;

-- name: UpdateMeasurementKinds :exec
UPDATE measurements
SET presence       = $2,
    magnitude      = $3,
    day_kind       = $4,
    count_kind     = $5,
    list_kind      = $6,
    maybe_kind     = $7,
    mark_kind      = $8,
    text_kind      = $9,
    spare_presence = $10,
    note           = $11,
    balance        = $12,
    closed_on      = $13
WHERE id = $1;
