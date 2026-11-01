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
 * @param schemaFiles the schema files of the entry, in the order they apply
 * @param queries the queries the entry's query source states correctly
 * @param rejections the queries of that source the parser rejected
 */
public record Source(
    String name,
    List<SchemaFile> schemaFiles,
    Path queriesPath,
    List<Query> queries,
    List<QueryRejection> rejections
) {
}
