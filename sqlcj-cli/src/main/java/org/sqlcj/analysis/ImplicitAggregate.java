package org.sqlcj.analysis;

/**
 * One projected aggregate that took the name PostgreSQL gives its column
 * instead of an alias: the {@code name} it took, and {@code written}, the
 * aggregate as the query writes it, which names the rejection of a name
 * another result column of the same projection already takes.
 */
record ImplicitAggregate(String name, String written) {
}
