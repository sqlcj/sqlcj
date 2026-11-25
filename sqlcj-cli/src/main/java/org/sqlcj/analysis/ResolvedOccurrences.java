package org.sqlcj.analysis;

import java.util.List;

/**
 * The resolved parameter occurrences of one query.
 *
 * @param occurrences  the parameter occurrence of each placeholder
 *                     occurrence the SQL parser reported, in textual order
 * @param writtenTypes the textual positions of the occurrences typed from
 *                     another occurrence, in ascending order, which are the
 *                     {@code ?} markers the executable SQL states a type at
 */
record ResolvedOccurrences(
    List<QueryParameter> occurrences,
    List<Integer> writtenTypes
) {
}
