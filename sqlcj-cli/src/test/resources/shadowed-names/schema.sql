-- A sqlcj-owned fixture whose enum types are named exactly like the JDK,
-- runtime, and JSpecify types generated code writes, so that every generated
-- file resolves the collision in itself: the generated enum keeps its simple
-- name and the type it shadows is written fully qualified there.
--
-- The table holds one column of every one of those enum types, non-null and
-- nullable, beside the numeric, date, and text columns whose Java types the
-- enums take the names of.

CREATE TYPE optional AS ENUM ('present', 'absent');

CREATE TYPE big_decimal AS ENUM ('small', 'large');

CREATE TYPE local_date AS ENUM ('today', 'tomorrow');

CREATE TYPE objects AS ENUM ('one', 'many');

CREATE TYPE arrays AS ENUM ('empty', 'filled');

CREATE TYPE nullable AS ENUM ('maybe', 'never');

CREATE TYPE null_marked AS ENUM ('marked', 'unmarked');

CREATE TYPE string AS ENUM ('text', 'blank');

CREATE TABLE measurements
(
    id             BIGSERIAL PRIMARY KEY,
    label          TEXT           NOT NULL,
    note           TEXT,
    total          NUMERIC(10, 2) NOT NULL,
    balance        NUMERIC(10, 2),
    taken_on       DATE           NOT NULL,
    closed_on      DATE,
    presence       optional       NOT NULL,
    magnitude      big_decimal    NOT NULL,
    day_kind       local_date     NOT NULL,
    count_kind     objects        NOT NULL,
    list_kind      arrays         NOT NULL,
    maybe_kind     nullable       NOT NULL,
    mark_kind      null_marked    NOT NULL,
    text_kind      string         NOT NULL,
    spare_presence optional
);
