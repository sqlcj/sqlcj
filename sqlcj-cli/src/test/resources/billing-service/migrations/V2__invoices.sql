-- Billing service, second migration: the invoices and their state.

CREATE TYPE invoice_state AS ENUM ('draft', 'sent', 'paid');

CREATE TABLE invoices
(
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id bigint         NOT NULL REFERENCES customers (id),
    state       invoice_state  NOT NULL DEFAULT 'draft',
    total       numeric(12, 2) NOT NULL,
    note        text
);

CREATE INDEX invoices_customer_idx ON invoices (customer_id, id);
