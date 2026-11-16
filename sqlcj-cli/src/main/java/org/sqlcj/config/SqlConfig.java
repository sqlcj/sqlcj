package org.sqlcj.config;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

/**
 * One configured query group.
 *
 * @param name the required group identity that names the generated repository
 * @param schema the ordered schema sources of the group, each a file or a
 *     directory of migration files
 * @param namespace the one PostgreSQL schema the group models, and {@code null}
 *     when the field states none, which models {@code public}
 */
public record SqlConfig(
    String name,
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> schema,
    String queries,
    String namespace
) {

    /** One configured schema source, the single-path form of the field. */
    public SqlConfig(String name, String schema, String queries) {
        this(name, schema == null ? null : List.of(schema), queries);
    }

    /** One group that names no schema of its own, which models {@code public}. */
    public SqlConfig(String name, List<String> schema, String queries) {
        this(name, schema, queries, null);
    }
}
