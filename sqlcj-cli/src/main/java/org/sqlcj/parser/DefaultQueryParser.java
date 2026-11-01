package org.sqlcj.parser;

import org.sqlcj.diagnostic.Suggestions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DefaultQueryParser implements QueryParser {

    private static final String HEADER_PREFIX = "-- name:";

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
        StringBuilder builder = new StringBuilder();

        for (String line : source.lines().toList()) {
            lineNumber++;

            if (line.startsWith(HEADER_PREFIX)) {
                if (currentName != null) {
                    complete(
                        queries,
                        rejections,
                        names,
                        builder,
                        currentName,
                        currentType,
                        currentLine
                    );
                }

                builder.setLength(0);

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
                builder
                    .append(line)
                    .append(System.lineSeparator());
            }
        }

        if (currentName != null) {
            complete(queries, rejections, names, builder, currentName, currentType, currentLine);
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
        StringBuilder builder,
        String currentName,
        QueryType currentType,
        int currentLine
    ) {
        String sql = builder.toString().trim();

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

        queries.add(
            new Query(
                currentName,
                currentType,
                sql,
                currentLine
            )
        );
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
