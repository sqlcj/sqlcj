package org.sqlcj.schema.parser;

/**
 * Signals that a schema source states something sqlcj cannot read or does not
 * support, at the line of the source it states it on.
 */
public class SchemaParseException extends RuntimeException {

    private final int line;

    public SchemaParseException(String message, int line, Throwable cause) {
        super(message, cause);

        this.line = line;
    }

    /**
     * The one-based line of the schema source the failure belongs to, or
     * {@code 0} when the failure names no line.
     */
    public int line() {
        return line;
    }
}
