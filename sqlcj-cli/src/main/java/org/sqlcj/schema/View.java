package org.sqlcj.schema;

/**
 * One view of the modeled namespace, recorded by its name and its kind alone.
 *
 * <p>sqlcj models no view's columns: a view statement states no column type
 * sqlcj can resolve from the schema source, so the statement is ignored for the
 * schema model. The name is recorded nonetheless, so that a query naming it
 * states that sqlcj does not model a view instead of reporting a missing table.
 *
 * @param name         the schema's declared name of the view, without its SQL
 *                     identifier delimiters and without the modeled schema
 *                     qualifier
 * @param materialized whether the view is a materialized view, which a
 *                     diagnostic names as PostgreSQL spells it
 */
public record View(
    String name,
    boolean materialized
) {
}
