--liquibase formatted sql

--changeset sqlcj:5 splitStatements:false
CREATE FUNCTION normalize_isbn(raw TEXT) RETURNS TEXT AS $body$
BEGIN
    RETURN replace(raw, '-', '');
END;
$body$ LANGUAGE plpgsql IMMUTABLE;
--rollback DROP FUNCTION normalize_isbn(TEXT);
