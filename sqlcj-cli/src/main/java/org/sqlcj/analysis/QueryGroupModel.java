package org.sqlcj.analysis;

import org.sqlcj.schema.EnumType;

import java.util.List;

/**
 * One analyzed query group and the queries its generated repository exposes,
 * in the order they were declared in the group's query source.
 *
 * @param name        the configured group identity that names the generated
 *                    repository
 * @param enums       the enum types the group's schema declares, which define
 *                    the labels of every enum column and parameter of its
 *                    queries
 * @param queriesPath the group's query source, relative to the directory of
 *                    the configuration file and with {@code /} between its name
 *                    elements, so the generated output is the same on every
 *                    machine, or {@code null} when it is unknown
 */
public record QueryGroupModel(
    String name,
    List<QueryModel> queries,
    List<EnumType> enums,
    String queriesPath
) {

    /** A group whose schema declares no enum type. */
    public QueryGroupModel(String name, List<QueryModel> queries) {
        this(name, queries, List.of());
    }

    /** A group whose query source is unknown. */
    public QueryGroupModel(String name, List<QueryModel> queries, List<EnumType> enums) {
        this(name, queries, enums, null);
    }
}
