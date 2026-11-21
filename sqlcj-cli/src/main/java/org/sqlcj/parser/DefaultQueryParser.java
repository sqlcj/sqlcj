package org.sqlcj.parser;

import org.sqlcj.diagnostic.Suggestions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class DefaultQueryParser implements QueryParser {

    private static final String HEADER_PREFIX = "-- name:";

    /** The start of one comment line of a query, at the line's first column. */
    private static final String COMMENT_PREFIX = "--";

    /**
     * The query annotations sqlcj accepts, in the order the rejection of an
     * unknown one lists them, which are also the candidates it suggests from.
     * {@link QueryType} parses three further annotations, which semantic
     * analysis rejects by the shape of their statement, so they are neither
     * listed nor suggested.
     */
    private static final List<String> ACCEPTED_ANNOTATIONS = List.of(
        ":one",
        ":optional",
        ":many",
        ":exec",
        ":batchexec"
    );

    /**
     * The one query option sqlcj accepts, which asks for the query's
     * parameters as one generated record, written as the header's third token.
     */
    private static final String PARAMETER_RECORD_OPTION = "params";

    /**
     * The query options sqlcj accepts, in the order the rejection of an unknown
     * one lists them, which are also the candidates it suggests from.
     */
    private static final List<String> ACCEPTED_OPTIONS = List.of(PARAMETER_RECORD_OPTION);

    /**
     * The rejection of the parameter-record option on a {@code :batchexec}
     * query, whose method already takes one generated record per row, so the
     * option would change nothing.
     */
    private static final String BATCH_PARAMETER_RECORD_REJECTION = """
        The query option 'params' has no effect on a :batchexec query, which already takes one \
        parameter record per row; remove it""";

    /**
     * Parses every query of one source, accepting the ones it states correctly
     * and rejecting the others, so that a run reports them all.
     *
     * <p>A header line always delimits its query, whether or not the name, the
     * annotation, and the option it writes parse, so a rejected query ends at
     * the next header and the lines up to it are skipped. A query is rejected
     * by an unreadable header, an annotation sqlcj does not parse, an option it
     * does not parse or cannot honor, a header without SQL, and a header that
     * repeats the name of an earlier accepted query.
     */
    @Override
    public ParsedQueries parse(String source) {
        List<Query> queries = new ArrayList<>();
        List<QueryRejection> rejections = new ArrayList<>();
        Set<String> names = new HashSet<>();

        QueryHeader currentHeader = null;
        int currentLine = 0;
        int lineNumber = 0;
        List<String> lines = new ArrayList<>();

        for (String line : source.lines().toList()) {
            lineNumber++;

            if (line.startsWith(HEADER_PREFIX)) {
                if (currentHeader != null) {
                    complete(queries, rejections, names, lines, currentHeader, currentLine);
                }

                lines.clear();

                ParsedHeader parsed = parseHeader(line);

                if (parsed.rejection() != null) {
                    rejections.add(new QueryRejection(lineNumber, parsed.rejection()));

                    currentHeader = null;

                    continue;
                }

                currentHeader = parsed.header();
                currentLine = lineNumber;

                continue;
            }

            if (currentHeader != null) {
                lines.add(line);
            }
        }

        if (currentHeader != null) {
            complete(queries, rejections, names, lines, currentHeader, currentLine);
        }

        return new ParsedQueries(queries, rejections);
    }

    /**
     * Accepts the query one read header states, or rejects it because it writes
     * no SQL or repeats the name of an earlier accepted query.
     */
    private void complete(
        List<Query> queries,
        List<QueryRejection> rejections,
        Set<String> names,
        List<String> lines,
        QueryHeader currentHeader,
        int currentLine
    ) {
        String sql = String.join(System.lineSeparator(), lines).trim();

        if (sql.isBlank()) {
            rejections.add(
                new QueryRejection(
                    currentLine,
                    "Query '" + currentHeader.name() + "' has no SQL"
                )
            );

            return;
        }

        if (!names.add(currentHeader.name())) {
            rejections.add(
                new QueryRejection(currentLine, "Duplicate query: " + currentHeader.name())
            );

            return;
        }

        List<String> comment = comment(lines);

        queries.add(
            new Query(
                currentHeader.name(),
                currentHeader.type(),
                sql,
                currentLine,
                comment,
                writtenSql(lines.subList(comment.size(), lines.size())),
                currentHeader.parameterRecord()
            )
        );
    }

    /**
     * The comment of one query: the lines directly under its header that begin
     * with {@code --}, up to the first line that does not, each without its
     * {@code --} and one following space. A bare {@code --} is an empty line.
     */
    private List<String> comment(List<String> lines) {
        List<String> comment = new ArrayList<>();

        for (String line : lines) {
            if (!line.startsWith(COMMENT_PREFIX)) {
                break;
            }

            String text = line.substring(COMMENT_PREFIX.length());

            comment.add(text.startsWith(" ") ? text.substring(1) : text);
        }

        return List.copyOf(comment);
    }

    /**
     * The SQL of one query as its source writes it: the lines after its
     * comment, without the blank lines before the first and after the last and
     * each other line exactly as written, joined by {@code \n}, so that the
     * documented text is the same on every platform.
     */
    private String writtenSql(List<String> lines) {
        int first = 0;
        int last = lines.size();

        while (first < last && lines.get(first).isBlank()) {
            first++;
        }

        while (last > first && lines.get(last - 1).isBlank()) {
            last--;
        }

        return String.join("\n", lines.subList(first, last));
    }

    /**
     * Reads one header line: the query name, its annotation, and the one option
     * it may carry after the annotation. A header of any other token count is
     * unreadable, the annotation is read before the option, and an option the
     * annotation cannot honor is rejected as such.
     */
    private ParsedHeader parseHeader(String line) {
        String header = line.substring(HEADER_PREFIX.length()).trim();
        String[] parts = header.split("\\s+");

        if (parts.length != 2 && parts.length != 3) {
            return new ParsedHeader(null, "Invalid query header: " + line);
        }

        String name = parts[0];
        String annotation = parts[1];

        Optional<QueryType> type = QueryType.from(annotation);

        if (type.isEmpty()) {
            return new ParsedHeader(null, unknownAnnotation(annotation));
        }

        if (parts.length == 2) {
            return new ParsedHeader(new QueryHeader(name, type.get(), false), null);
        }

        String option = parts[2];

        if (!PARAMETER_RECORD_OPTION.equals(option)) {
            return new ParsedHeader(null, unknownOption(option));
        }

        if (type.get() == QueryType.BATCH_EXEC) {
            return new ParsedHeader(null, BATCH_PARAMETER_RECORD_REJECTION);
        }

        return new ParsedHeader(new QueryHeader(name, type.get(), true), null);
    }

    /**
     * The rejection of an annotation sqlcj does not parse: the annotation as it
     * is written, the accepted annotations, and the one closest to the written
     * text when there is one. The header's line is stated by the location of
     * the diagnostic the compiler builds from this reason.
     */
    private String unknownAnnotation(String annotation) {
        String suggestion = Suggestions.closest(annotation, ACCEPTED_ANNOTATIONS)
            .map(closest -> "; did you mean '" + closest + "'?")
            .orElse(".");

        return "Unknown query annotation '%s'%s Use one of %s".formatted(
            annotation,
            suggestion,
            String.join(", ", ACCEPTED_ANNOTATIONS)
        );
    }

    /**
     * The rejection of an option sqlcj does not parse: the option as it is
     * written, the accepted options, and the one closest to the written text
     * when there is one. The header's line is stated by the location of the
     * diagnostic the compiler builds from this reason.
     */
    private String unknownOption(String option) {
        String suggestion = Suggestions.closest(option, ACCEPTED_OPTIONS)
            .map(closest -> "; did you mean '" + closest + "'?")
            .orElse(".");

        return "Unknown query option '%s'%s Use %s".formatted(
            option,
            suggestion,
            String.join(", ", ACCEPTED_OPTIONS)
        );
    }

    /**
     * One read header line: the header it states, or the reason sqlcj rejected
     * it. Exactly one of the two is present.
     */
    private record ParsedHeader(QueryHeader header, String rejection) {
    }
}
