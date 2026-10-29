package org.sqlcj.spring.boot.autoconfigure.repositories;

import org.sqlcj.runtime.QueryExecutor;

/**
 * Stands for a repository sqlcj generated: a top-level public final class
 * carrying the marker, taking its {@link QueryExecutor} as its only
 * constructor argument.
 *
 * <p>Unlike generated code it exposes that executor, so a test can assert
 * which executor the registered bean was built from.
 */
@org.sqlcj.runtime.GeneratedRepository
public final class NotesRepository {

    private final QueryExecutor executor;

    public NotesRepository(QueryExecutor executor) {
        this.executor = executor;
    }

    public QueryExecutor executor() {
        return executor;
    }
}
