package org.sqlcj.analysis;

import org.sqlcj.schema.Schema;
import org.sqlcj.sql.ParsedSql;

/**
 * The inputs every analyzed {@code SELECT} of one statement shares: the
 * parsed source, the schema it resolves against, the placeholders its
 * clauses type, and the wildcards its projections expand.
 */
record Analysis(
    ParsedSql parsedSql,
    Schema schema,
    Placeholders placeholders,
    Wildcards wildcards
) {

    /** The analysis of one statement, before any clause is analyzed. */
    Analysis(ParsedSql parsedSql, Schema schema, Placeholders placeholders) {
        this(parsedSql, schema, placeholders, new Wildcards());
    }
}
