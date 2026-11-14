package org.sqlcj.sql;

import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the token-span handling of {@link SqlParameterCompiler} with
 * fabricated tokens, which is the only way to reach the guards that protect the
 * compiler from a span the parser cannot describe, and its reading of a real
 * parse tree through {@link SqlParser}.
 */
class SqlParameterCompilerTest {

    private final SqlParameterCompiler compiler = new SqlParameterCompiler();

    @Test
    void shouldReplaceReportedParameterSpans() {
        String sql = "SELECT * FROM users WHERE id = $1";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.S_PARAMETER, "$1", 31)
        );

        assertEquals("SELECT * FROM users WHERE id = ?", parameters.executableSql());
        assertEquals(List.of(1), parameters.indexes());
        assertEquals(List.of(31), parameters.offsets());
    }

    /**
     * Each replacement reports the offset its own {@code ?} takes in the
     * compiled SQL, which a shorter replacement before it and placeholder text
     * no token reported, such as the {@code $1} of a string literal, both move.
     */
    @Test
    void shouldReportTheOffsetOfEachCompiledMarker() {
        String sql = "SELECT id FROM users WHERE tag = '$1' AND code = $2 AND id = $1";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.S_PARAMETER, "$2", 49),
            token(CCJSqlParserConstants.S_PARAMETER, "$1", 61)
        );

        assertEquals(
            "SELECT id FROM users WHERE tag = '$1' AND code = ? AND id = ?",
            parameters.executableSql()
        );

        assertEquals(List.of(2, 1), parameters.indexes());
        assertEquals(List.of(49, 60), parameters.offsets());

        assertEquals(
            '?',
            parameters.executableSql().charAt(parameters.offsets().get(0))
        );
    }

    @Test
    void shouldIgnoreTokenThatIsNotAParameter() {
        String sql = "SELECT a$1b FROM users";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.S_IDENTIFIER, "a$1b", 7)
        );

        assertEquals(sql, parameters.executableSql());
        assertTrue(parameters.indexes().isEmpty());
    }

    @Test
    void shouldIgnoreParameterTokenWithoutIndexDigits() {
        String sql = "SELECT * FROM users WHERE id = $";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.S_PARAMETER, "$", 31)
        );

        assertEquals(sql, parameters.executableSql());
        assertTrue(parameters.indexes().isEmpty());
    }

    @Test
    void shouldRejectSpanThatDoesNotHoldTheTokenImage() {
        String sql = "SELECT * FROM users WHERE id = $1";

        Token parameter = token(CCJSqlParserConstants.S_PARAMETER, "$1", 30);

        SqlParseException exception = assertThrows(
            SqlParseException.class,
            () -> compile(sql, parameter)
        );

        assertTrue(
            exception.getMessage()
                .startsWith("Parameter '$1' reported an unusable source position"),
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectSpanOutsideTheSource() {
        String sql = "SELECT * FROM users WHERE id = $1";

        Token parameter = token(CCJSqlParserConstants.S_PARAMETER, "$1", sql.length());

        assertThrows(
            SqlParseException.class,
            () -> compile(sql, parameter)
        );
    }

    @Test
    void shouldReplaceReportedNamedParameterSpans() {
        String sql = "SELECT * FROM users WHERE id = :id";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.DOUBLE_COLON, ":", 31),
            token(CCJSqlParserConstants.S_IDENTIFIER, "id", 32)
        );

        assertEquals("SELECT * FROM users WHERE id = ?", parameters.executableSql());
        assertEquals(List.of(1), parameters.indexes());
        assertEquals(List.of("id"), parameters.names());
    }

    /** A name that does not follow its colon directly is other text. */
    @Test
    void shouldIgnoreNameSeparatedFromItsColon() {
        String sql = "SELECT * FROM users WHERE id = : id";

        SqlParameters parameters = compile(
            sql,
            token(CCJSqlParserConstants.DOUBLE_COLON, ":", 31),
            token(CCJSqlParserConstants.S_IDENTIFIER, "id", 33)
        );

        assertEquals(sql, parameters.executableSql());
        assertTrue(parameters.indexes().isEmpty());
        assertTrue(parameters.names().isEmpty());
    }

    @Test
    void shouldRejectNamedSpanThatDoesNotHoldTheParameterImage() {
        String sql = "SELECT * FROM users WHERE id = :id";

        Token colon = token(CCJSqlParserConstants.DOUBLE_COLON, ":", 30);
        Token name = token(CCJSqlParserConstants.S_IDENTIFIER, "id", 31);

        SqlParseException exception = assertThrows(
            SqlParseException.class,
            () -> compile(sql, colon, name)
        );

        assertTrue(
            exception.getMessage()
                .startsWith("Parameter ':id' reported an unusable source position"),
            exception.getMessage()
        );
    }

    @Test
    void shouldRejectSpansThatAreNotInTextualOrder() {
        String sql = "SELECT * FROM users WHERE id = $1 AND id = $2";

        Token second = token(CCJSqlParserConstants.S_PARAMETER, "$2", 43);
        Token first = token(CCJSqlParserConstants.S_PARAMETER, "$1", 31);

        assertThrows(
            SqlParseException.class,
            () -> compile(sql, second, first)
        );
    }

    /**
     * The parser gives the operand of a {@code ::} cast no parse-tree node of
     * its own, so the cast reports the placeholder it casts, through a chained
     * cast of the same operand.
     */
    @Test
    void shouldReportNamedParametersThatAreCastOperands() {
        String sql = "SELECT * FROM users WHERE id = :\"x\"::bigint AND code = &y::int::bigint";

        SqlParameters parameters = compile(
            sql,
            new Token[] { token(CCJSqlParserConstants.S_IDENTIFIER, "SELECT", 0) },
            cast(namedParameter(":", "\"x\"")),
            cast(cast(namedParameter("&", "y")))
        );

        assertEquals(sql, parameters.executableSql());
        assertTrue(parameters.indexes().isEmpty());

        assertEquals(
            List.of(":\"x\"", "&y"),
            parameters.uncompiledPlaceholders()
        );
    }

    /** A cast operand this compiler replaced is not reported again. */
    @Test
    void shouldNotReportCompiledNamedParameterThatIsACastOperand() {
        String sql = "SELECT * FROM users WHERE id = :x::bigint";

        SqlParameters parameters = compile(
            sql,
            new Token[] {
                token(CCJSqlParserConstants.DOUBLE_COLON, ":", 31),
                token(CCJSqlParserConstants.S_IDENTIFIER, "x", 32)
            },
            cast(namedParameter(":", "x"))
        );

        assertEquals("SELECT * FROM users WHERE id = ?::bigint", parameters.executableSql());
        assertEquals(List.of(1), parameters.indexes());
        assertEquals(List.of("x"), parameters.names());
        assertTrue(parameters.uncompiledPlaceholders().isEmpty());
    }

    /**
     * A placeholder the query tests for null is reported as null tested: the
     * operand of {@code IS [NOT] NULL}, including its {@code ISNULL} and
     * {@code NOTNULL} spellings, either side of
     * {@code IS [NOT] DISTINCT FROM}, and an argument of an unqualified
     * {@code COALESCE} or {@code NULLIF}.
     */
    @Test
    void shouldReportTheNullTestedPositionsOfAPlaceholder() {
        assertEquals(List.of(true), nullTests("SELECT id FROM users WHERE :a IS NULL"));
        assertEquals(List.of(true), nullTests("SELECT id FROM users WHERE :a IS NOT NULL"));
        assertEquals(List.of(true), nullTests("SELECT id FROM users WHERE :a ISNULL"));
        assertEquals(List.of(true), nullTests("SELECT id FROM users WHERE :a NOTNULL"));

        assertEquals(
            List.of(true, true),
            nullTests("SELECT id FROM users WHERE :a IS DISTINCT FROM :b")
        );

        assertEquals(
            List.of(true),
            nullTests("SELECT id FROM users WHERE name IS NOT DISTINCT FROM :a")
        );

        assertEquals(
            List.of(true, true),
            nullTests("SELECT id FROM users WHERE COALESCE(:a, :b) = name")
        );

        assertEquals(
            List.of(true, true),
            nullTests("SELECT id FROM users WHERE NULLIF(:a, :b) = name")
        );
    }

    /**
     * The tested value is the placeholder itself, a cast chain of it, or a
     * parenthesized one, so a placeholder another expression computes with is
     * not tested, and neither is one a clause only compares.
     */
    @Test
    void shouldReportOnlyTheTestedValueItselfAsNullTested() {
        assertEquals(List.of(true), nullTests("SELECT id FROM users WHERE :a::text IS NULL"));

        assertEquals(
            List.of(true),
            nullTests("SELECT id FROM users WHERE :a::text::varchar IS NULL")
        );

        assertEquals(List.of(true), nullTests("SELECT id FROM users WHERE (:a) IS NULL"));
        assertEquals(List.of(true), nullTests("SELECT id FROM users WHERE (:a)::text IS NULL"));

        assertEquals(List.of(false), nullTests("SELECT id FROM users WHERE lower(:a) IS NULL"));
        assertEquals(List.of(false), nullTests("SELECT id FROM users WHERE name = :a"));
        assertEquals(List.of(false), nullTests("SELECT id FROM users WHERE id IN (:a)"));
        assertEquals(
            List.of(false),
            nullTests("SELECT id FROM users WHERE pg_catalog.coalesce(:a, name) = name")
        );
    }

    /**
     * A null test is reported wherever it stands, which includes an
     * {@code IS NULL} the parse tree gives no node of its own, as the guard of
     * a criteria query does, and one inside a subquery, while the other
     * occurrences of the same name stay untested.
     */
    @Test
    void shouldReportANullTestAtEveryNestingDepth() {
        assertEquals(
            List.of(true, false),
            nullTests("SELECT id FROM users WHERE (:a::text IS NULL OR name = :a)")
        );

        assertEquals(
            List.of(false, true),
            nullTests("SELECT id FROM users WHERE name = :a AND (:a IS NULL OR id > 0)")
        );

        assertEquals(
            List.of(true),
            nullTests(
                "SELECT id FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE :a::text IS NULL)"
            )
        );

        assertEquals(
            List.of(true),
            nullTests("UPDATE users SET name = 'x' WHERE :a::text IS NULL")
        );
    }

    /**
     * The JSONB key-existence operators are written doubled, which is the
     * escape the JDBC driver reads as the operator itself, and the offset
     * reported for a placeholder written after one accounts for the character
     * the doubling adds.
     */
    @Test
    void shouldWriteTheJsonKeyExistenceOperatorsDoubled() {
        SqlParameters key = parameters("SELECT id FROM docs WHERE meta ? 'vip'");

        assertEquals("SELECT id FROM docs WHERE meta ?? 'vip'", key.executableSql());
        assertFalse(key.hasAnonymousParameter());

        SqlParameters anyKey = parameters("SELECT id FROM docs WHERE meta ?| array['a','b']");

        assertEquals(
            "SELECT id FROM docs WHERE meta ??| array['a','b']",
            anyKey.executableSql()
        );

        SqlParameters allKeys = parameters(
            "SELECT id FROM docs WHERE meta ?& array['a'] AND n = :n"
        );

        assertEquals(
            "SELECT id FROM docs WHERE meta ??& array['a'] AND n = ?",
            allKeys.executableSql()
        );

        assertEquals(List.of(54), allKeys.offsets());
        assertEquals('?', allKeys.executableSql().charAt(54));
    }

    /**
     * A placeholder written as the right operand of the operator is replaced
     * at the position the doubled operator leaves it at.
     */
    @Test
    void shouldReportPlaceholderOffsetsAfterADoubledJsonKeyOperator() {
        SqlParameters named = parameters("SELECT id FROM docs WHERE meta ? :key");

        assertEquals("SELECT id FROM docs WHERE meta ?? ?", named.executableSql());
        assertEquals(List.of(34), named.offsets());
        assertEquals(List.of("key"), named.names());

        SqlParameters indexed = parameters("SELECT id FROM docs WHERE meta ?| $1");

        assertEquals("SELECT id FROM docs WHERE meta ??| ?", indexed.executableSql());
        assertEquals(List.of(35), indexed.offsets());
        assertEquals(List.of(1), indexed.indexes());
    }

    /**
     * A {@code ?} the source protects is neither the operator nor a
     * placeholder, so a string literal, a dollar-quoted string, a quoted
     * identifier, and a comment keep it as written.
     */
    @Test
    void shouldLeaveAProtectedQuestionMarkUnchanged() {
        assertUnchanged("SELECT id FROM docs WHERE tag = '?'");
        assertUnchanged("SELECT id FROM docs WHERE tag = $$?$$");
        assertUnchanged("SELECT id, \"a?b\" FROM docs");
        assertUnchanged("SELECT id FROM docs WHERE n = 1 -- ?\n");
        assertUnchanged("SELECT id FROM docs WHERE n = 1 /* ? */");
    }

    /**
     * A real anonymous placeholder is still reported where the operator stands
     * beside it, on either side of the operator.
     */
    @Test
    void shouldReportAnAnonymousParameterBesideAJsonKeyOperator() {
        SqlParameters right = parameters("SELECT id FROM docs WHERE meta ? ?");

        assertEquals("SELECT id FROM docs WHERE meta ?? ?", right.executableSql());
        assertTrue(right.hasAnonymousParameter());

        SqlParameters left = parameters("SELECT id FROM docs WHERE ? ? 'a'");

        assertEquals("SELECT id FROM docs WHERE ? ?? 'a'", left.executableSql());
        assertTrue(left.hasAnonymousParameter());
    }

    /** Requires that one source compiles to itself and binds nothing. */
    private void assertUnchanged(String sql) {
        SqlParameters parameters = parameters(sql);

        assertEquals(sql, parameters.executableSql());
        assertTrue(parameters.offsets().isEmpty());
        assertFalse(parameters.hasAnonymousParameter());
    }

    /** Whether the query tests each of its placeholder occurrences for null. */
    private List<Boolean> nullTests(String sql) {
        return new SqlParser().parse(sql).placeholders().stream()
            .map(PlaceholderOccurrence::nullTested)
            .toList();
    }

    /** The compiled parameters of one source, as the real parser reports it. */
    private SqlParameters parameters(String sql) {
        return new SqlParser().parse(sql).parameters();
    }

    private SqlParameters compile(String sql, Token... tokens) {
        return compiler.compile(sql, node(tokens), new ArrayList<>(), new ArrayList<>());
    }

    /**
     * Compiles a source whose parse tree holds the given expressions beside its
     * tokens, which is how the compiler sees a placeholder the parser reports
     * without a parse-tree node of its own.
     */
    private SqlParameters compile(String sql, Token[] tokens, Expression... expressions) {
        Node root = node(tokens);

        for (int index = 0; index < expressions.length; index++) {
            Node child = new Node(index + 1);

            child.jjtSetValue(expressions[index]);

            root.jjtAddChild(child, index);
        }

        return compiler.compile(sql, root, new ArrayList<>(), new ArrayList<>());
    }

    private Node node(Token... tokens) {
        for (int index = 0; index + 1 < tokens.length; index++) {
            tokens[index].next = tokens[index + 1];
        }

        Node node = new Node(0);
        node.jjtSetFirstToken(tokens[0]);
        node.jjtSetLastToken(tokens[tokens.length - 1]);

        return node;
    }

    private CastExpression cast(Expression operand) {
        CastExpression cast = new CastExpression();

        cast.setLeftExpression(operand);
        cast.setColDataType(new ColDataType("bigint"));

        return cast;
    }

    private JdbcNamedParameter namedParameter(String parameterCharacter, String name) {
        return new JdbcNamedParameter(name)
            .setParameterCharacter(parameterCharacter);
    }

    /**
     * Builds a token whose reported position follows the parser convention of
     * counting the first source character as position one.
     */
    private Token token(int kind, String image, int offset) {
        Token token = new Token(kind, image);
        token.absoluteBegin = offset + 1;
        token.absoluteEnd = token.absoluteBegin + image.length();

        return token;
    }
}
