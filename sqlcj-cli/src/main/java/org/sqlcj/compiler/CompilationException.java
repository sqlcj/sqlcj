package org.sqlcj.compiler;

import java.util.List;

/**
 * Signals that configured SQL sources cannot be loaded or compiled, or that
 * their generated output cannot be written.
 *
 * <p>A run reports every rejected query at once, so the failure carries an
 * ordered list of diagnostics rather than one message. Every other failure
 * carries the single diagnostic that ended the run.
 */
public class CompilationException extends RuntimeException {

    private final List<String> diagnostics;

    public CompilationException(String message) {
        this(List.of(message), null);
    }

    public CompilationException(String message, Throwable cause) {
        this(List.of(message), cause);
    }

    public CompilationException(List<String> diagnostics) {
        this(diagnostics, null);
    }

    private CompilationException(List<String> diagnostics, Throwable cause) {
        super(String.join(System.lineSeparator(), diagnostics), cause);

        this.diagnostics = List.copyOf(diagnostics);
    }

    /** The diagnostics of the failure, in the order they are reported. */
    public List<String> diagnostics() {
        return diagnostics;
    }
}
