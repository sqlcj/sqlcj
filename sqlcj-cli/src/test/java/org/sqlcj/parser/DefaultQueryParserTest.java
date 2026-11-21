package org.sqlcj.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryParserTest {

    private final QueryParser parser = new DefaultQueryParser();

    /** The names of the accepted queries, in order. */
    private static List<String> names(ParsedQueries parsed) {
        return parsed.queries()
            .stream()
            .map(Query::name)
            .toList();
    }

    @Test
    void parsesSingleQuery() {
        String source = """
            -- name: GetUser :one
            SELECT *
            FROM users
            WHERE id = $1;
            """;

        List<Query> queries = parser.parse(source).queries();

        assertEquals(1, queries.size());

        Query query = queries.get(0);

        assertEquals("GetUser", query.name());
        assertEquals(QueryType.ONE, query.type());
        assertEquals("""
            SELECT *
            FROM users
            WHERE id = $1;
            """.trim(), query.sql());
    }

    @Test
    void parsesMultipleQueries() {
        String source = """
            -- name: GetUser :one
            SELECT *
            FROM users
            WHERE id = $1;

            -- name: ListUsers :many
            SELECT *
            FROM users;
            """;

        List<Query> queries = parser.parse(source).queries();

        assertEquals(2, queries.size());

        assertEquals("GetUser", queries.get(0).name());
        assertEquals(QueryType.ONE, queries.get(0).type());

        Query second = queries.get(1);

        assertEquals("ListUsers", second.name());
        assertEquals(QueryType.MANY, second.type());
    }

    @Test
    void parsesOptionalQueryType() {
        String source = """
            -- name: FindUser :optional
            SELECT *
            FROM users
            WHERE id = $1;
            """;

        List<Query> queries = parser.parse(source).queries();

        assertEquals(1, queries.size());
        assertEquals("FindUser", queries.get(0).name());
        assertEquals(QueryType.OPTIONAL, queries.get(0).type());
    }

    @Test
    void parsesExecQueryType() {
        String source = """
            -- name: DeleteUser :exec
            DELETE FROM users
            WHERE id = $1;
            """;

        List<Query> queries = parser.parse(source).queries();

        assertEquals(1, queries.size());
        assertEquals(QueryType.EXEC, queries.get(0).type());
    }

    @Test
    void rejectsInvalidQueryType() {
        String source = """
            -- name: GetUser :invalid
            SELECT *
            FROM users;
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of(), parsed.queries());
        assertEquals(
            List.of(
                new QueryRejection(
                    1,
                    "Unknown query annotation ':invalid'."
                        + " Use one of :one, :optional, :many, :exec, :batchexec"
                )
            ),
            parsed.rejections()
        );
    }

    @Test
    void suggestsClosestAnnotationAtItsHeaderLine() {
        String source = """
            -- name: GetUser :one
            SELECT *
            FROM users
            WHERE id = $1;

            -- name: ListUsers :mnay
            SELECT *
            FROM users;
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of("GetUser"), names(parsed));
        assertEquals(
            List.of(
                new QueryRejection(
                    6,
                    "Unknown query annotation ':mnay'; did you mean ':many'?"
                        + " Use one of :one, :optional, :many, :exec, :batchexec"
                )
            ),
            parsed.rejections()
        );
    }

    @Test
    void suggestsAnnotationForDifferentlyCasedAnnotation() {
        String source = """
            -- name: ListUsers :MANY
            SELECT *
            FROM users;
            """;

        assertEquals(
            List.of(
                new QueryRejection(
                    1,
                    "Unknown query annotation ':MANY'; did you mean ':many'?"
                        + " Use one of :one, :optional, :many, :exec, :batchexec"
                )
            ),
            parser.parse(source).rejections()
        );
    }

    @Test
    void suggestsAnnotationForAnnotationWithoutColon() {
        String source = """
            -- name: ListUsers many
            SELECT *
            FROM users;
            """;

        assertEquals(
            List.of(
                new QueryRejection(
                    1,
                    "Unknown query annotation 'many'; did you mean ':many'?"
                        + " Use one of :one, :optional, :many, :exec, :batchexec"
                )
            ),
            parser.parse(source).rejections()
        );
    }

    @Test
    void suggestsNoAnnotationForFarAnnotation() {
        String source = """
            -- name: ListUsers :stream
            SELECT *
            FROM users;
            """;

        assertEquals(
            List.of(
                new QueryRejection(
                    1,
                    "Unknown query annotation ':stream'."
                        + " Use one of :one, :optional, :many, :exec, :batchexec"
                )
            ),
            parser.parse(source).rejections()
        );
    }

    /**
     * A header may carry the {@code params} option after its annotation, which
     * asks for the query's parameters as one record. A header without it asks
     * for none.
     */
    @Test
    void parsesParameterRecordOption() {
        String source = """
            -- name: InsertUser :exec params
            INSERT INTO users (id, name)
            VALUES ($1, $2);

            -- name: DeleteUser :exec
            DELETE FROM users
            WHERE id = $1;
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of(), parsed.rejections());
        assertEquals(List.of("InsertUser", "DeleteUser"), names(parsed));

        assertTrue(parsed.queries().get(0).parameterRecord());
        assertFalse(parsed.queries().get(1).parameterRecord());
    }

    /**
     * An option sqlcj does not parse is rejected at its own header line, naming
     * the accepted option and, by the suggestion rule, the closest one: both a
     * colon-prefixed {@code :params} and a misspelled {@code parms} are
     * answered with {@code params}.
     */
    @Test
    void suggestsClosestOptionAtItsHeaderLine() {
        String source = """
            -- name: GetUser :one
            SELECT *
            FROM users
            WHERE id = $1;

            -- name: InsertUser :exec :params
            INSERT INTO users (id)
            VALUES ($1);

            -- name: UpdateUser :exec parms
            UPDATE users
            SET name = $2
            WHERE id = $1;
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of("GetUser"), names(parsed));
        assertEquals(
            List.of(
                new QueryRejection(
                    6,
                    "Unknown query option ':params'; did you mean 'params'? Use params"
                ),
                new QueryRejection(
                    10,
                    "Unknown query option 'parms'; did you mean 'params'? Use params"
                )
            ),
            parsed.rejections()
        );
    }

    /** An option no accepted option is close to keeps its diagnostic. */
    @Test
    void suggestsNoOptionForFarOption() {
        String source = """
            -- name: InsertUser :exec struct
            INSERT INTO users (id)
            VALUES ($1);
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of(), parsed.queries());
        assertEquals(
            List.of(new QueryRejection(1, "Unknown query option 'struct'. Use params")),
            parsed.rejections()
        );
    }

    /** A header of more than three tokens is unreadable. */
    @Test
    void rejectsHeaderOfMoreThanThreeTokens() {
        String source = """
            -- name: InsertUser :exec params extra
            INSERT INTO users (id)
            VALUES ($1);
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of(), parsed.queries());
        assertEquals(
            List.of(
                new QueryRejection(
                    1,
                    "Invalid query header: -- name: InsertUser :exec params extra"
                )
            ),
            parsed.rejections()
        );
    }

    /**
     * The option is rejected on a {@code :batchexec} query, whose method
     * already takes one parameter record per row.
     */
    @Test
    void rejectsParameterRecordOptionOnBatchQuery() {
        String source = """
            -- name: InsertUsers :batchexec params
            INSERT INTO users (id, name)
            VALUES ($1, $2);
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of(), parsed.queries());
        assertEquals(
            List.of(
                new QueryRejection(
                    1,
                    "The query option 'params' has no effect on a :batchexec query, which "
                        + "already takes one parameter record per row; remove it"
                )
            ),
            parsed.rejections()
        );
    }

    /**
     * A header that repeats the name of an earlier accepted query is rejected
     * at its own line, and the query it repeats is kept.
     */
    @Test
    void rejectsDuplicateQueryNames() {
        String source = """
            -- name: GetUser :one
            SELECT *
            FROM users
            WHERE id = $1;

            -- name: GetUser :many
            SELECT *
            FROM users;
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of("GetUser"), names(parsed));
        assertEquals(QueryType.ONE, parsed.queries().get(0).type());
        assertEquals(
            List.of(new QueryRejection(6, "Duplicate query: GetUser")),
            parsed.rejections()
        );
    }

    @Test
    void rejectsQueryWithoutSql() {
        String source = """
            -- name: GetUser :one
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of(), parsed.queries());
        assertEquals(
            List.of(new QueryRejection(1, "Query 'GetUser' has no SQL")),
            parsed.rejections()
        );
    }

    /**
     * A header without SQL is rejected at its own line and the query that
     * follows it is still parsed, because the next header ends it.
     */
    @Test
    void rejectsQueryWithoutSqlBesideALaterQuery() {
        String source = """
            -- name: GetUser :one

            -- name: ListUsers :many
            SELECT *
            FROM users;
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of("ListUsers"), names(parsed));
        assertEquals(
            List.of(new QueryRejection(1, "Query 'GetUser' has no SQL")),
            parsed.rejections()
        );
    }

    /**
     * Each rejected header ends at the next header, so two rejected headers are
     * both reported, in the order the source writes them, the accepted query
     * between them is parsed, and the SQL of a rejected header is skipped
     * rather than attached to another query.
     */
    @Test
    void rejectsEveryHeaderItCannotReadBesideAnAcceptedQuery() {
        String source = """
            -- name: ListUsers :mnay
            SELECT *
            FROM users;

            -- name: GetUser :one
            SELECT *
            FROM users
            WHERE id = $1;

            -- name:
            SELECT 1;
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of("GetUser"), names(parsed));
        assertEquals("""
            SELECT *
            FROM users
            WHERE id = $1;
            """.trim(), parsed.queries().get(0).sql());

        assertEquals(
            List.of(
                new QueryRejection(
                    1,
                    "Unknown query annotation ':mnay'; did you mean ':many'?"
                        + " Use one of :one, :optional, :many, :exec, :batchexec"
                ),
                new QueryRejection(10, "Invalid query header: -- name:")
            ),
            parsed.rejections()
        );
    }

    @Test
    void returnsEmptyListWhenNoQueriesExist() {
        String source = """
            SELECT *
            FROM users;
            """;

        List<Query> queries = parser.parse(source).queries();

        assertTrue(queries.isEmpty());
    }

    @Test
    void rejectsInvalidHeader() {
        String source = """
            -- name:
            SELECT *
            FROM users;
            """;

        ParsedQueries parsed = parser.parse(source);

        assertEquals(List.of(), parsed.queries());
        assertEquals(
            List.of(new QueryRejection(1, "Invalid query header: -- name:")),
            parsed.rejections()
        );
    }

    @Test
    void preservesQueryOrder() {
        String source = """
            -- name: First :one
            SELECT 1;

            -- name: Second :one
            SELECT 2;

            -- name: Third :one
            SELECT 3;
            """;

        List<Query> queries = parser.parse(source).queries();

        assertEquals(3, queries.size());

        assertEquals("First", queries.get(0).name());
        assertEquals("Second", queries.get(1).name());
        assertEquals("Third", queries.get(2).name());
    }

    /**
     * The comment of a query is the lines directly under its header that begin
     * with {@code --}, each without its {@code --} and one following space, a
     * bare {@code --} as an empty line, and the SQL shown is the text the
     * source writes after them.
     */
    @Test
    void parsesCommentBlockUnderHeader() {
        String source = """
            -- name: GetUser :one
            -- Reads one user by id.
            --
            --Written without a space.
            SELECT *
            FROM users
            WHERE id = $1;
            """;

        Query query = parser.parse(source).queries().get(0);

        assertEquals(
            List.of("Reads one user by id.", "", "Written without a space."),
            query.comment()
        );

        assertEquals("""
            SELECT *
            FROM users
            WHERE id = $1;""", query.writtenSql());
    }

    /** A blank line ends the comment, and the lines after it are the SQL. */
    @Test
    void endsCommentBlockAtABlankLine() {
        String source = """
            -- name: GetUser :one
            -- Reads one user by id.

            -- Not part of the comment.
            SELECT *
            FROM users
            WHERE id = $1;
            """;

        Query query = parser.parse(source).queries().get(0);

        assertEquals(List.of("Reads one user by id."), query.comment());

        assertEquals("""
            -- Not part of the comment.
            SELECT *
            FROM users
            WHERE id = $1;""", query.writtenSql());
    }

    /** A comment between SQL lines stays in the SQL, where it was written. */
    @Test
    void keepsACommentBetweenSqlLinesInTheWrittenSql() {
        String source = """
            -- name: ListUsers :many
            SELECT *
            -- Only the active ones.
            FROM users
            WHERE active;
            """;

        Query query = parser.parse(source).queries().get(0);

        assertEquals(List.of(), query.comment());

        assertEquals("""
            SELECT *
            -- Only the active ones.
            FROM users
            WHERE active;""", query.writtenSql());
    }

    /**
     * A comment above a later header belongs to the query before it, which a
     * sqlcj query owns every line up to the next header.
     */
    @Test
    void keepsACommentAboveALaterHeaderInTheEarlierQuery() {
        String source = """
            -- name: GetUser :one
            SELECT *
            FROM users
            WHERE id = $1;

            -- Lists every user.
            -- name: ListUsers :many
            SELECT *
            FROM users;
            """;

        List<Query> queries = parser.parse(source).queries();

        assertEquals("""
            SELECT *
            FROM users
            WHERE id = $1;

            -- Lists every user.""", queries.get(0).writtenSql());

        assertEquals(List.of(), queries.get(1).comment());

        assertEquals("""
            SELECT *
            FROM users;""", queries.get(1).writtenSql());
    }

    /**
     * The SQL shown keeps the indentation of its first line, which the trimmed
     * {@code sql} of the same query drops.
     */
    @Test
    void keepsTheIndentationOfTheFirstSqlLine() {
        String source = """
            -- name: GetUser :one
                SELECT *
                FROM users
                WHERE id = $1;
            """;

        Query query = parser.parse(source).queries().get(0);

        assertEquals(
            "    SELECT *\n    FROM users\n    WHERE id = $1;",
            query.writtenSql()
        );

        assertEquals(
            "SELECT *\n    FROM users\n    WHERE id = $1;",
            query.sql()
        );
    }

    @Test
    void parsesHeaderLineOfEachQuery() {
        String source = """
            -- A leading comment.

            -- name: GetUser :one
            SELECT *
            FROM users
            WHERE id = $1;

            -- name: ListUsers :many
            SELECT *
            FROM users;
            """;

        List<Query> queries = parser.parse(source).queries();

        assertEquals(3, queries.get(0).line());
        assertEquals(8, queries.get(1).line());
    }
}
