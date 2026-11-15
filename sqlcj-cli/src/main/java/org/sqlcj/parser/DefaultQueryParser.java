package org.sqlcj.parser;

import org.sqlcj.diagnostic.Suggestions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
     * Parses every query of one source, accepting the ones it states correctly
     * and rejecting the others, so that a run reports them all.
     *
     * <p>A header line always delimits its query, whether or not the name and
     * the annotation it writes parse, so a rejected query ends at the next
     * header and the lines up to it are skipped. A query is rejected by an
     * unreadable header, an annotation sqlcj does not parse, a header without
     * SQL, and a header that repeats the name of an earlier accepted query.
     */
    @Override
    public ParsedQueries parse(String source) {
        List<Query> queries = new ArrayList<>();
        List<QueryRejection> rejections = new ArrayList<>();
        Set<String> names = new HashSet<>();

        String currentName = null;
        QueryType currentType = null;
        int currentLine = 0;
        int lineNumber = 0;
        List<String> lines = new ArrayList<>();

        for (String line : source.lines().toList()) {
            lineNumber++;

            if (line.startsWith(HEADER_PREFIX)) {
                if (currentName != null) {
                    complete(
                        queries,
                        rejections,
                        names,
                        lines,
                        currentName,
                        currentType,
                        currentLine
                    );
                }

                lines.clear();

                ParsedHeader parsed = parseHeader(line);

                if (parsed.rejection() != null) {
                    rejections.add(new QueryRejection(lineNumber, parsed.rejection()));

                    currentName = null;
                    currentType = null;

                    continue;
                }

                currentName = parsed.header().name();
                currentType = parsed.header().type();
                currentLine = lineNumber;

                continue;
            }

            if (currentName != null) {
                lines.add(line);
            }
        }

        if (currentName != null) {
            complete(queries, rejections, names, lines, currentName, currentType, currentLine);
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
        String currentName,
        QueryType currentType,
        int currentLine
    ) {
        String sql = String.join(System.lineSeparator(), lines).trim();

        if (sql.isBlank()) {
            rejections.add(
                new QueryRejection(currentLine, "Query '" + currentName + "' has no SQL")
            );

            return;
        }

        if (!names.add(currentName)) {
            rejections.add(new QueryRejection(currentLine, "Duplicate query: " + currentName));

            return;
        }

        List<String> comment = comment(lines);

        queries.add(
            new Query(
                currentName,
                currentType,
                sql,
                currentLine,
                comment,
                writtenSql(lines.subList(comment.size(), lines.size()))
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

    private ParsedHeader parseHeader(String line) {
        String header = line.substring(HEADER_PREFIX.length()).trim();
        String[] parts = header.split("\\s+");

        if (parts.length != 2) {
            return new ParsedHeader(null, "Invalid query header: " + line);
        }

        String name = parts[0];
        String annotation = parts[1];

        return QueryType.from(annotation)
            .map(type -> new ParsedHeader(new QueryHeader(name, type), null))
            .orElseGet(() -> new ParsedHeader(null, unknownAnnotation(annotation)));
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
     * One read header line: the header it states, or the reason sqlcj rejected
     * it. Exactly one of the two is present.
     */
    private record ParsedHeader(QueryHeader header, String rejection) {
    }
}
