package org.sqlcj.spring.boot.autoconfigure.repositories;

import org.sqlcj.runtime.QueryExecutor;

/**
 * A hand-written class in the same package that takes a {@link QueryExecutor}
 * but carries no marker, so the starter must leave it unregistered.
 */
public final class UnmarkedRepository {

    private final QueryExecutor executor;

    public UnmarkedRepository(QueryExecutor executor) {
        this.executor = executor;
    }

    public QueryExecutor executor() {
        return executor;
    }
}
