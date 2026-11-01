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

    @Override
    public List<Query> parse(String source) {
        List<Query> queries = new ArrayList<>();

        String currentName = null;
        QueryType currentType = null;
        int currentLine = 0;
        int lineNumber = 0;
        StringBuilder builder = new StringBuilder();

        for (String line : source.lines().toList()) {
            lineNumber++;

            if (line.startsWith(HEADER_PREFIX)) {
                if (currentName != null) {
                    queries.add(buildQuery(builder, currentName, currentType, currentLine));
                }

                QueryHeader header = parseHeader(line, lineNumber);
                currentName = header.name();
                currentType = header.type();
                currentLine = lineNumber;

                builder.setLength(0);
                continue;
            }

            if (currentName != null) {
                builder
                    .append(line)
                    .append(System.lineSeparator());
            }
        }

        if (currentName != null) {
            queries.add(buildQuery(builder, currentName, currentType, currentLine));
        }

        Set<String> names = new HashSet<>();
        for (Query query : queries) {
            if (!names.add(query.name())) {
                throw new IllegalArgumentException(
                    "Duplicate query: " + query.name()
                );
            }
        }

        return queries;
    }

    private Query buildQuery(
        StringBuilder builder,
        String currentName,
        QueryType currentType,
        int currentLine
    ) {
        String sql = builder.toString().trim();

        if (sql.isBlank()) {
            throw new IllegalArgumentException(
                "Query '" + currentName + "' has no SQL"
            );
        }

        return new Query(
            currentName,
            currentType,
            sql,
            currentLine
        );
    }

    private QueryHeader parseHeader(String line, int lineNumber) {
        String header = line.substring(HEADER_PREFIX.length()).trim();
        String[] parts = header.split("\\s+");

        if (parts.length != 2) {
            throw new IllegalArgumentException(
                "Invalid query header: " + line
            );
        }

        String name = parts[0];
        String annotation = parts[1];
        QueryType type = QueryType.from(annotation)
            .orElseThrow(() -> unknownAnnotation(annotation, lineNumber));

        return new QueryHeader(name, type);
    }

    /**
     * The rejection of an annotation sqlcj does not parse: the annotation as it
     * is written, the line of the header that writes it, the accepted
     * annotations, and the one closest to the written text when there is one.
     */
    private IllegalArgumentException unknownAnnotation(String annotation, int lineNumber) {
        String suggestion = Suggestions.closest(annotation, ACCEPTED_ANNOTATIONS)
            .map(closest -> "; did you mean '" + closest + "'?")
            .orElse(".");

        return new IllegalArgumentException(
            "Unknown query annotation '%s' at line %d%s Use one of %s".formatted(
                annotation,
                lineNumber,
                suggestion,
                String.join(", ", ACCEPTED_ANNOTATIONS)
            )
        );
    }
}
