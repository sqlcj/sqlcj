package org.sqlcj.analysis;

import org.sqlcj.parser.QueryType;

import java.util.List;

/**
 * Analyzed query facts required by code generation.
 *
 * @param table                    the name this query writes for the source its
 *                                 rows come from, or {@code null} when the
 *                                 query reads no source at all
 * @param executableSql            JDBC-executable SQL where every parsed
 *                                 {@code $N} and {@code :name} parameter token
 *                                 is replaced by {@code ?}, written
 *                                 {@code ?::<type>} where the occurrence takes
 *                                 its type from another occurrence of the same
 *                                 logical parameter, every wildcard select item
 *                                 this query was analyzed with is replaced by
 *                                 the delimited column list it was analyzed as,
 *                                 so that a column the database gains later
 *                                 never shifts a value the mapper reads, and
 *                                 every other character is preserved
 * @param bindingParameterIndexes  the logical parameter number of each
 *                                 {@code ?} position in
 *                                 {@link #executableSql()}, in textual order
 * @param parameters               one query parameter per logical parameter
 *                                 number, in that order
 * @param rowTable                 the schema's declared name of the table whose
 *                                 complete row this query returns, or
 *                                 {@code null} when the result is specific to
 *                                 this query
 * @param comment                  the comment lines written directly under the
 *                                 query's header, in source order
 * @param writtenSql               the query's SQL as its source writes it,
 *                                 which the generated method documents
 * @param line                     the one-based line of the query header in its
 *                                 source, or {@code 0} when it is unknown
 * @param parameterRecord          whether the query's header carries the
 *                                 {@code params} option, so that its
 *                                 parameters are one generated record
 * @param placeholders             the placeholder of each logical parameter, in
 *                                 the order of {@link #parameters()} and as the
 *                                 query source writes it, such as {@code $1},
 *                                 {@code :id}, and {@code :page.size}, which
 *                                 the generated documentation names. A
 *                                 {@code $N} parameter is named after its
 *                                 column, so its placeholder cannot be
 *                                 recovered from its name
 */
public record QueryModel(
    String name,
    QueryType type,
    String table,
    String executableSql,
    List<Integer> bindingParameterIndexes,
    List<QueryColumn> columns,
    List<QueryParameter> parameters,
    String rowTable,
    List<String> comment,
    String writtenSql,
    int line,
    boolean parameterRecord,
    List<String> placeholders
) {

    public QueryModel {
        placeholders = List.copyOf(placeholders);
    }

    /** A query documented by its executable SQL alone, at no known line. */
    public QueryModel(
        String name,
        QueryType type,
        String table,
        String executableSql,
        List<Integer> bindingParameterIndexes,
        List<QueryColumn> columns,
        List<QueryParameter> parameters,
        String rowTable
    ) {
        this(
            name,
            type,
            table,
            executableSql,
            bindingParameterIndexes,
            columns,
            parameters,
            rowTable,
            List.of(),
            executableSql,
            0
        );
    }

    /** A query whose header carries no option. */
    public QueryModel(
        String name,
        QueryType type,
        String table,
        String executableSql,
        List<Integer> bindingParameterIndexes,
        List<QueryColumn> columns,
        List<QueryParameter> parameters,
        String rowTable,
        List<String> comment,
        String writtenSql,
        int line
    ) {
        this(
            name,
            type,
            table,
            executableSql,
            bindingParameterIndexes,
            columns,
            parameters,
            rowTable,
            comment,
            writtenSql,
            line,
            false
        );
    }

    /**
     * A query whose placeholders are the ones its parameters are written with
     * by default: {@code :<group>.<name>} for a parameter of a group, which
     * carries both, and {@code $<index>} for every other one.
     */
    public QueryModel(
        String name,
        QueryType type,
        String table,
        String executableSql,
        List<Integer> bindingParameterIndexes,
        List<QueryColumn> columns,
        List<QueryParameter> parameters,
        String rowTable,
        List<String> comment,
        String writtenSql,
        int line,
        boolean parameterRecord
    ) {
        this(
            name,
            type,
            table,
            executableSql,
            bindingParameterIndexes,
            columns,
            parameters,
            rowTable,
            comment,
            writtenSql,
            line,
            parameterRecord,
            defaultPlaceholders(parameters)
        );
    }

    /** The placeholder each parameter of a query is written with by default. */
    private static List<String> defaultPlaceholders(List<QueryParameter> parameters) {
        return parameters.stream()
            .map(
                parameter -> parameter.group() == null
                    ? "$" + parameter.index()
                    : ":" + parameter.group() + "." + parameter.name()
            )
            .toList();
    }
}
