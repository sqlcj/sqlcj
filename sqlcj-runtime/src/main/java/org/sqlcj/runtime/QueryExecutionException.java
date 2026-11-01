package org.sqlcj.runtime;

import org.jspecify.annotations.Nullable;

public class QueryExecutionException extends RuntimeException {

    public QueryExecutionException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
