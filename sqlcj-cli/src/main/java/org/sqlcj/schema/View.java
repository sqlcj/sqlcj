package org.sqlcj.schema;

import java.util.List;

/**
 * One view of the modeled namespace, recorded by the definition its statement
 * states.
 *
 * <p>sqlcj models no view's columns in the schema: a view's columns stand in
 * its own query, which the analyzer resolves against the schema recorded here
 * when a query names the view, so the schema model stays syntax-level and a
 * view whose query sqlcj cannot analyze fails only the queries that read it.
 *
 * <p>PostgreSQL fixes a view's output columns when it is created, so the
 * recorded schema is the schema as it stood just before the statement: a
 * column a later statement adds to an underlying table is no column of the
 * view, as PostgreSQL leaves it out.
 *
 * <p>Two later statements do reach the view PostgreSQL holds: what they state
 * is recorded in {@code columnsNullable} and {@code enumRenames} beside the
 * recorded schema, which stays as it stood, because the defining query may
 * name a type one of them renames by the name it had there.
 *
 * @param name            the schema's declared name of the view, without its
 *                        SQL identifier delimiters and without the modeled
 *                        schema qualifier
 * @param materialized    whether the view is a materialized view, which a
 *                        diagnostic names as PostgreSQL spells it
 * @param query           the text of the defining query, which is the text
 *                        after the {@code AS} of the statement without its
 *                        trailing {@code WITH CHECK OPTION} or
 *                        {@code WITH [NO] DATA} clause
 * @param columnNames     the declared names of the column list written after
 *                        the view's name, as the statement writes them, SQL
 *                        identifier delimiters included, and empty where the
 *                        statement writes no list
 * @param schema          the schema as it stood just before the statement,
 *                        which the defining query is analyzed against
 * @param recursive       whether the statement states {@code RECURSIVE}, which
 *                        makes a view one sqlcj does not analyze
 * @param columnsNullable whether a later {@code ALTER TABLE ... ALTER COLUMN
 *                        ... DROP NOT NULL} of a modeled table stands after
 *                        the statement, which makes every column of the view
 *                        nullable, because sqlcj does not track which table
 *                        column a view column reads
 * @param enumRenames     the enum type renames stated after the statement, in
 *                        their order, which rename the enum type of the view's
 *                        columns
 */
public record View(
    String name,
    boolean materialized,
    String query,
    List<String> columnNames,
    Schema schema,
    boolean recursive,
    boolean columnsNullable,
    List<EnumRename> enumRenames
) {

    public View {
        columnNames = List.copyOf(columnNames);
        enumRenames = List.copyOf(enumRenames);
    }

    /** A view no later statement of the schema source reaches. */
    public View(
        String name,
        boolean materialized,
        String query,
        List<String> columnNames,
        Schema schema,
        boolean recursive
    ) {
        this(
            name,
            materialized,
            query,
            columnNames,
            schema,
            recursive,
            false,
            List.of()
        );
    }

    /**
     * One enum type rename stated after the view was created, which the
     * analyzer applies to the enum type the view's own analysis resolves.
     *
     * @param oldName the declared name the type had where the view was created
     * @param newName the declared name {@code RENAME TO} states
     */
    public record EnumRename(String oldName, String newName) {
    }
}
