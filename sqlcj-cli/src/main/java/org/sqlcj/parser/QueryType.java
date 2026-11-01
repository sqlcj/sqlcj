package org.sqlcj.parser;

import java.util.Optional;

public enum QueryType {
    ONE,
    OPTIONAL,
    MANY,
    EXEC,
    EXEC_RESULT,
    BATCH_EXEC,
    BATCH_MANY,
    BATCH_ONE;

    /**
     * The query type one annotation names, matched as the annotation is
     * spelled, or an empty result when no annotation of that spelling is
     * parsed. The query parser reports an unknown annotation, because the
     * diagnostic states the header's line.
     */
    public static Optional<QueryType> from(String value) {
        return switch (value) {
            case ":one" -> Optional.of(ONE);
            case ":optional" -> Optional.of(OPTIONAL);
            case ":many" -> Optional.of(MANY);
            case ":exec" -> Optional.of(EXEC);
            case ":execresult" -> Optional.of(EXEC_RESULT);
            case ":batchexec" -> Optional.of(BATCH_EXEC);
            case ":batchmany" -> Optional.of(BATCH_MANY);
            case ":batchone" -> Optional.of(BATCH_ONE);
            default -> Optional.empty();
        };
    }
}
