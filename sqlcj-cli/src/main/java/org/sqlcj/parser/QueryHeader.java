package org.sqlcj.parser;

/**
 * One parsed query header.
 *
 * @param parameterRecord whether the header carries the {@code params} option,
 *                        which asks for one generated record of the query's
 *                        parameters
 */
public record QueryHeader(
    String name,
    QueryType type,
    boolean parameterRecord
) {
}
