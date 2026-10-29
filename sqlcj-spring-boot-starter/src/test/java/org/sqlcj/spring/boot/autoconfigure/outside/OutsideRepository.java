package org.sqlcj.spring.boot.autoconfigure.outside;

import org.sqlcj.runtime.QueryExecutor;

/**
 * A marked repository in a package that is not an auto-configuration package,
 * so the starter must leave it unregistered.
 */
@org.sqlcj.runtime.GeneratedRepository
public final class OutsideRepository {

    private final QueryExecutor executor;

    public OutsideRepository(QueryExecutor executor) {
        this.executor = executor;
    }

    public QueryExecutor executor() {
        return executor;
    }
}
