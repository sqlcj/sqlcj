package org.sqlcj.compiler;

import org.sqlcj.parser.Query;
import org.sqlcj.parser.QueryRejection;

import java.nio.file.Path;
import java.util.List;

/**
 * One loaded configuration entry and the paths it was loaded from, which
 * identify a source in a compilation diagnostic.
 *
 * @param name the configured group identity that names the generated repository
 * @param namespace the one PostgreSQL schema the entry models, which is
 *     {@code public} when its configuration names none
 * @param schemaFiles the schema files of the entry, in the order they apply
 * @param queries the queries the entry's query source states correctly
 * @param rejections the queries of that source the parser rejected
 */
public record Source(
    String name,
    String namespace,
    List<SchemaFile> schemaFiles,
    Path queriesPath,
    List<Query> queries,
    List<QueryRejection> rejections
) {
}
