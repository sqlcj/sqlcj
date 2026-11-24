package org.sqlcj.analysis;

import org.sqlcj.schema.parser.ColumnTypeMapping;

/**
 * One placeholder occurrence a cast types, and the type that cast states.
 */
record CastPlaceholder(Placeholder placeholder, ColumnTypeMapping.MappedType type) {
}
