-- Outside the supported set: a wildcard selects an unmapped column.
-- name: GetOrganization :optional
SELECT *
FROM organizations
WHERE id = :id;
