-- The source the function catalogue's queries read, and the sequence
-- nextval() advances.

CREATE SEQUENCE event_number_seq;

CREATE TABLE events
(
    id       bigint      NOT NULL PRIMARY KEY,
    label    text        NOT NULL,
    nickname varchar(50),
    code     char(4),
    score    smallint,
    visits   integer     NOT NULL,
    total    bigint,
    amount   numeric(12, 2),
    at_day   date,
    at_local timestamp,
    at_zoned timestamptz NOT NULL,
    span     interval
);
