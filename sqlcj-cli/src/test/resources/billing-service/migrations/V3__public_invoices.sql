-- Billing service, third migration: the reporting table another team keeps in
-- public, named like this service's invoices and holding other columns, which
-- the billing group models nowhere.

CREATE TABLE public.invoices
(
    id    bigint NOT NULL,
    label text   NOT NULL
);
