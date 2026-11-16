-- The named SQL of the billing service's Billing group, whose schema is
-- billing. A query names this service's tables unqualified, as the application
-- writes them over a connection whose search_path names billing, or qualified
-- with billing.

-- name: CreateCustomer :one
INSERT INTO customers (email, name)
VALUES (:email, :name)
RETURNING *;

-- name: GetCustomerByEmail :optional
SELECT id,
       email,
       name
FROM customers
WHERE email = :email;

-- name: CreateInvoice :one
INSERT INTO invoices (customer_id, state, total, note)
VALUES (:customerId, :state, :total, :note)
RETURNING id, customer_id, state, total;

-- name: ListCustomerInvoices :many
SELECT *
FROM billing.invoices
WHERE customer_id = :customerId
ORDER BY id;

-- name: MarkInvoiceSent :exec
UPDATE invoices
SET state = :state
WHERE id = :id
  AND state = :currentState;
