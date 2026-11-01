package org.sqlcj.sql;

import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.parser.Token;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The corrective fact of a SQL syntax failure, such as {@code Encountered
 * unexpected token: ";"}.
 *
 * <p>The SQL parser reports a syntax failure through a chain of wrapping
 * exceptions whose outer messages repeat the class name of the failure they
 * wrap, and whose {@link ParseException} states the unexpected token with
 * parser-internal token kinds and lexical states. The fact is therefore
 * composed from the unexpected token that chain carries, or taken from the
 * innermost cause when it carries no {@link ParseException}, and never names an
 * exception class or a parser internal.
 */
public final class SqlParseReason {

    /**
     * The line a failure that states its own position names, such as the
     * lexical sentence {@code Lexical error at line 4, column 0. ...}, which
     * exposes its position no other way.
     */
    private static final Pattern STATED_LINE = Pattern.compile("at line (\\d+)");

    private SqlParseReason() {
    }

    /**
     * The corrective fact of one SQL syntax failure, optionally followed by the
     * column of the unexpected token.
     *
     * <p>The line is not part of the reason: a caller that reports a position
     * states it as the location of its diagnostic, taken from
     * {@link #lineOf(Throwable)}.
     */
    public static String of(Throwable failure, boolean withColumn) {
        ParseException parseFailure = parseFailure(failure);

        if (parseFailure == null) {
            return firstLine(innermostCause(failure));
        }

        Token token = parseFailure.currentToken == null
            ? null
            : parseFailure.currentToken.next;

        if (token == null) {
            return firstLine(parseFailure);
        }

        String reason = unexpected(token);

        if (!withColumn) {
            return reason;
        }

        return "%s at column %d".formatted(reason, token.beginColumn);
    }

    /**
     * The line of one SQL syntax failure in the source it was parsed from, or
     * {@code 0} when the failure states none: the line of the unexpected token,
     * or the line the innermost cause's own sentence states, as a lexical
     * failure does.
     */
    public static int lineOf(Throwable failure) {
        ParseException parseFailure = parseFailure(failure);

        if (parseFailure != null) {
            Token token = parseFailure.currentToken == null
                ? null
                : parseFailure.currentToken.next;

            if (token != null) {
                return token.beginLine;
            }
        }

        return statedLine(firstLine(innermostCause(failure)));
    }

    /** The line one failure's own sentence states, or {@code 0}. */
    private static int statedLine(String sentence) {
        Matcher matcher = STATED_LINE.matcher(sentence);

        return matcher.find()
            ? Integer.parseInt(matcher.group(1))
            : 0;
    }

    /**
     * States the token the parser did not expect. The end of input has no image
     * to quote, and a token image that spans lines is quoted with its line
     * breaks escaped so the reason stays one line.
     */
    private static String unexpected(Token token) {
        if (token.kind == CCJSqlParserConstants.EOF) {
            return "Encountered unexpected end of input";
        }

        return "Encountered unexpected token: \"%s\"".formatted(
            token.image
                .replace("\r", "\\r")
                .replace("\n", "\\n")
        );
    }

    /** The parse failure the exception chain carries, or {@code null}. */
    private static ParseException parseFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ParseException parseFailure) {
                return parseFailure;
            }
        }

        return null;
    }

    /**
     * The innermost cause of the exception chain, which states a lexical failure
     * in its own wording while every wrapper around it repeats its class name.
     */
    private static Throwable innermostCause(Throwable failure) {
        Throwable cause = failure;

        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        return cause;
    }

    private static String firstLine(Throwable failure) {
        String message = failure.getMessage();

        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }

        return message.lines()
            .findFirst()
            .orElse(message)
            .trim();
    }
}
