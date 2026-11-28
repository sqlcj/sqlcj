package org.sqlcj.sql;

import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.CCJSqlParserTokenManager;
import net.sf.jsqlparser.parser.ParserKeywordsUtils;
import net.sf.jsqlparser.parser.SimpleCharStream;
import net.sf.jsqlparser.parser.StringProvider;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.parser.TokenMgrException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Hands the SQL parser a word PostgreSQL does not reserve as a name, where the
 * tokens around that word prove it to be one.
 *
 * <p>PostgreSQL accepts every word its {@code pg_get_keywords()} lists in
 * category {@code U} or {@code C} unquoted as a table, column, or alias name,
 * and so accepts any word that is no keyword of its own at all. The SQL parser
 * reserves words of its own instead, and offers no option that unreserves one,
 * so a schema with a {@code settings} column or a query over a {@code day}
 * column does not parse.
 *
 * <p>Nothing is rewritten here. One piece of SQL is lexed with the parser's own
 * token manager before it is parsed, the tokens that stand for names are
 * classified by the rules below, and the parser is then handed a token manager
 * that reports exactly those tokens as identifiers. Every token image, every
 * line and column, and every byte of the executable SQL the parameter compiler
 * copies from the source therefore stay as the user wrote them, and a word
 * keeps its keyword meaning wherever the rules do not prove it a name.
 *
 * <p>A candidate is a single-word token the lexer reports as a keyword and
 * whose lower-case image PostgreSQL does not reserve; see
 * {@link #POSTGRESQL_RESERVED}. A reserved candidate is a candidate the parser
 * itself reserves. In a query, a slot that a non-reserved word already reaches
 * takes a reserved candidate only, because such a word has keyword uses of its
 * own there, as in {@code PARTITION BY}, {@code ROWS}, and {@code NULLS LAST}.
 * In a table statement every candidate is taken.
 *
 * <p>In a query, and in a view body, a candidate is a name directly before or
 * after a dot; directly after {@code AS}, unless it stands inside the
 * parentheses of a type-taking function or opens a call of its own; in the
 * table slot of {@code FROM}, {@code JOIN}, {@code INTO}, {@code USING}, or a
 * statement-opening {@code UPDATE}, unless it begins a function call or a typed
 * literal; as the alias directly after such a table; and as an operand, which
 * is a token that both follows an expression opener and is followed by a token
 * that ends an operand, except after a {@code NOT} that negates an operator
 * rather than opening an expression, as in {@code IS NOT} and
 * {@code NOT BETWEEN}, and as the field of {@code EXTRACT}.
 *
 * <p>In a {@code CREATE}, {@code ALTER}, or {@code DROP TABLE} statement a name
 * is the altered table, a table after {@code REFERENCES}, {@code LIKE}, or
 * {@code PARTITION OF}, a table of {@code INHERITS} or of a {@code DROP TABLE}
 * list, the first word of a {@code CREATE TABLE} element, the column of an
 * {@code ADD}, {@code DROP}, {@code ALTER}, or {@code RENAME} action, and an
 * operand of a constraint expression or column list. Every other schema
 * statement is parsed exactly as it is without this pass.
 */
public final class KeywordNames {

    /**
     * The words PostgreSQL 16 reserves, which are the words its
     * {@code pg_get_keywords()} lists in category {@code R} or {@code T}.
     * PostgreSQL itself requires each of them quoted to be read as a name, so
     * none of them is a candidate here.
     */
    static final Set<String> POSTGRESQL_RESERVED = Set.of(
        "all",
        "analyse",
        "analyze",
        "and",
        "any",
        "array",
        "as",
        "asc",
        "asymmetric",
        "authorization",
        "binary",
        "both",
        "case",
        "cast",
        "check",
        "collate",
        "collation",
        "column",
        "concurrently",
        "constraint",
        "create",
        "cross",
        "current_catalog",
        "current_date",
        "current_role",
        "current_schema",
        "current_time",
        "current_timestamp",
        "current_user",
        "default",
        "deferrable",
        "desc",
        "distinct",
        "do",
        "else",
        "end",
        "except",
        "false",
        "fetch",
        "for",
        "foreign",
        "freeze",
        "from",
        "full",
        "grant",
        "group",
        "having",
        "ilike",
        "in",
        "initially",
        "inner",
        "intersect",
        "into",
        "is",
        "isnull",
        "join",
        "lateral",
        "leading",
        "left",
        "like",
        "limit",
        "localtime",
        "localtimestamp",
        "natural",
        "not",
        "notnull",
        "null",
        "offset",
        "on",
        "only",
        "or",
        "order",
        "outer",
        "overlaps",
        "placing",
        "primary",
        "references",
        "returning",
        "right",
        "select",
        "session_user",
        "similar",
        "some",
        "symmetric",
        "system_user",
        "table",
        "tablesample",
        "then",
        "to",
        "trailing",
        "true",
        "union",
        "unique",
        "user",
        "using",
        "variadic",
        "verbose",
        "when",
        "where",
        "window",
        "with"
    );

    /**
     * One identifier word, as the lexer reports it. A keyword token whose image
     * is not one word, such as the {@code current date} the lexer glues into one
     * token, stands for no name.
     */
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * The words PostgreSQL allows between the verb and {@code TABLE} of a table
     * statement.
     */
    private static final Set<String> TABLE_MODIFIERS = Set.of(
        "GLOBAL",
        "LOCAL",
        "TEMP",
        "TEMPORARY",
        "UNLOGGED",
        "FOREIGN"
    );

    /**
     * The functions whose parentheses take a type name after {@code AS}, where a
     * candidate states that type and not an alias.
     */
    private static final Set<String> TYPE_TAKING_FUNCTIONS = Set.of(
        "CAST",
        "TRY_CAST",
        "XMLSERIALIZE",
        "TREAT"
    );

    /**
     * The tokens a query operand may stand directly after: an operator, an
     * opening parenthesis, a comma, or a clause word that opens an expression.
     */
    private static final Set<String> QUERY_OPERAND_PREFIXES = Set.of(
        "SELECT",
        "DISTINCT",
        "WHERE",
        "AND",
        "OR",
        "NOT",
        "ON",
        "HAVING",
        "WHEN",
        "THEN",
        "ELSE",
        "CASE",
        "BY",
        "RETURNING",
        "SET",
        "LIKE",
        "ILIKE",
        "BETWEEN",
        "(",
        ",",
        "[",
        "=",
        "<>",
        "!=",
        "<",
        ">",
        "<=",
        ">=",
        "+",
        "-",
        "*",
        "/",
        "%",
        "^",
        "||",
        "->",
        "->>",
        "#>",
        "#>>",
        "@>",
        "<@",
        "&&",
        "~",
        "~*",
        "!~",
        "!~*"
    );

    /**
     * The tokens an operand of a table statement may stand directly after. A
     * table statement has no clause that opens an expression the way
     * {@code SELECT} or {@code WHERE} does, so only operators, an opening
     * parenthesis, a comma, the words of a condition, and the {@code USING} of
     * an altered type are openers there.
     */
    private static final Set<String> TABLE_OPERAND_PREFIXES = Set.of(
        "AND",
        "OR",
        "NOT",
        "WHEN",
        "THEN",
        "ELSE",
        "CASE",
        "LIKE",
        "ILIKE",
        "BETWEEN",
        "USING",
        "(",
        ",",
        "[",
        "=",
        "<>",
        "!=",
        "<",
        ">",
        "<=",
        ">=",
        "+",
        "-",
        "*",
        "/",
        "%",
        "^",
        "||",
        "->",
        "->>",
        "#>",
        "#>>",
        "@>",
        "<@",
        "&&",
        "~",
        "~*",
        "!~",
        "!~*"
    );

    /**
     * The tokens that end an operand: an operator, a closing parenthesis, a
     * comma, the statement's end, or a word that opens the next part of the
     * statement. A keyword use such as {@code interval '1' day} or
     * {@code CURRENT ROW} is followed by none of them, so checking both sides of
     * an operand leaves every such use its keyword meaning.
     */
    private static final Set<String> OPERAND_FOLLOWERS = Set.of(
        ",",
        ")",
        "]",
        ";",
        "",
        "=",
        "<>",
        "!=",
        "<",
        ">",
        "<=",
        ">=",
        "+",
        "-",
        "*",
        "/",
        "%",
        "^",
        "||",
        "::",
        "->",
        "->>",
        "#>",
        "#>>",
        "@>",
        "<@",
        "&&",
        "~",
        "~*",
        "!~",
        "!~*",
        "[",
        "AS",
        "FROM",
        "WHERE",
        "GROUP",
        "ORDER",
        "HAVING",
        "LIMIT",
        "OFFSET",
        "FETCH",
        "UNION",
        "INTERSECT",
        "EXCEPT",
        "WINDOW",
        "FOR",
        "RETURNING",
        "ON",
        "USING",
        "JOIN",
        "INNER",
        "LEFT",
        "RIGHT",
        "FULL",
        "CROSS",
        "NATURAL",
        "AND",
        "OR",
        "IS",
        "IN",
        "NOT",
        "LIKE",
        "ILIKE",
        "BETWEEN",
        "THEN",
        "WHEN",
        "ELSE",
        "END",
        "ASC",
        "DESC",
        "NULLS",
        "COLLATE"
    );

    private KeywordNames() {
    }

    /**
     * A parser consumer that reads the names of one query, or of one view body,
     * and then hands the parser on to {@code next}.
     *
     * <p>{@code CCJSqlParserUtil} calls the consumer for every parser it
     * creates, so the returned consumer relabels the tokens of each parse of
     * {@code sql}, the complex parse a simple parse's failure leads to included.
     */
    public static Consumer<CCJSqlParser> queryNames(String sql, Consumer<CCJSqlParser> next) {
        return consumer(sql, names(sql, false), next);
    }

    /**
     * A parser consumer that reads the names of one {@code CREATE},
     * {@code ALTER}, or {@code DROP TABLE} statement and then hands the parser
     * on to {@code next}. Every other statement keeps the names the parser
     * itself reads, so {@code sql} that opens any other way is classified as
     * nothing.
     */
    public static Consumer<CCJSqlParser> tableStatementNames(String sql, Consumer<CCJSqlParser> next) {
        return consumer(sql, names(sql, true), next);
    }

    /**
     * A consumer that re-initializes the parser with a token manager over
     * {@code sql} that reports the token beginning at each offset of
     * {@code names} as an identifier.
     *
     * <p>The parser is left alone when the SQL states no such name at all, so
     * SQL without one parses exactly as it does without this pass.
     */
    private static Consumer<CCJSqlParser> consumer(
        String sql,
        Set<Integer> names,
        Consumer<CCJSqlParser> next
    ) {
        Consumer<CCJSqlParser> relabel = parser -> {
            if (!names.isEmpty()) {
                parser.ReInit(identifierTokenManager(sql, names));
            }
        };

        return next == null
            ? relabel
            : relabel.andThen(next);
    }

    /**
     * A token manager over {@code sql} that reports the token beginning at each
     * offset of {@code names} as an identifier and every other token as the
     * lexer reads it. A token is identified by its offset in the source, which
     * the lexer records on every token it reports.
     */
    private static CCJSqlParserTokenManager identifierTokenManager(String sql, Set<Integer> names) {
        return new CCJSqlParserTokenManager(charStream(sql)) {

            @Override
            public Token getNextToken() {
                Token token = super.getNextToken();

                if (names.contains(token.absoluteBegin)) {
                    token.kind = CCJSqlParserConstants.S_IDENTIFIER;
                }

                return token;
            }
        };
    }

    /**
     * The offsets of the tokens of {@code sql} that stand for names, by the
     * rules of a query or of a table statement. A lexical error classifies
     * nothing, so the parser reports it as it does without this pass.
     */
    static Set<Integer> names(String sql, boolean tableStatement) {
        List<Token> tokens = lex(sql);

        if (tokens.isEmpty()) {
            return Set.of();
        }

        return new Pass(tokens, tableStatement).offsets();
    }

    /**
     * The tokens of one piece of SQL, in order and without the end-of-file
     * token, or no token at all when the lexer rejects the source.
     */
    private static List<Token> lex(String sql) {
        CCJSqlParserTokenManager tokenManager = new CCJSqlParserTokenManager(charStream(sql));
        List<Token> tokens = new ArrayList<>();

        try {
            while (true) {
                Token token = tokenManager.getNextToken();

                if (token.kind == CCJSqlParserConstants.EOF) {
                    return tokens;
                }

                tokens.add(token);
            }
        } catch (TokenMgrException e) {
            return List.of();
        }
    }

    private static SimpleCharStream charStream(String sql) {
        return new SimpleCharStream(new StringProvider(sql), 1, 1);
    }

    /** One classification of one piece of SQL, over the tokens the lexer read. */
    private static final class Pass {

        private final List<Token> tokens;

        /** Whether the rules of a table statement apply instead of a query's. */
        private final boolean tableStatement;

        /**
         * The upper-case image of the word before the innermost parenthesis that
         * encloses each token, or the empty string where none does, which names
         * the function whose arguments the token stands among.
         */
        private final String[] enclosingWords;

        /** The indexes of the tokens classified as names, in order. */
        private final Set<Integer> names = new TreeSet<>();

        /**
         * The indexes of the first token of each {@code CREATE TABLE} element and
         * each {@code ALTER TABLE} action, which the operand rule never takes.
         */
        private final Set<Integer> slotStarts = new HashSet<>();

        private Pass(List<Token> tokens, boolean tableStatement) {
            this.tokens = tokens;
            this.tableStatement = tableStatement;
            this.enclosingWords = enclosingWords(tokens);
        }

        /** The source offsets of the names this pass reads. */
        private Set<Integer> offsets() {
            if (tableStatement) {
                classifyTableStatement();
            } else {
                classifyQueryTables();
            }

            classifyExpressions();

            Set<Integer> offsets = new HashSet<>();

            for (int index : names) {
                offsets.add(tokens.get(index).absoluteBegin);
            }

            return offsets;
        }

        /**
         * Classifies the table of each table slot of a query and the alias that
         * follows it.
         *
         * <p>A table slot opens at {@code FROM}, {@code JOIN}, {@code INTO},
         * {@code USING}, or an {@code UPDATE} that opens the statement or follows
         * a closing parenthesis, which is the {@code UPDATE} of a statement and
         * not the {@code DO UPDATE} of an upsert. A candidate followed by an
         * opening parenthesis begins a function call, and one followed by a
         * string literal a typed literal, so neither states a table; after
         * {@code INTO} a parenthesis opens the inserted column list instead.
         *
         * <p>An alias is read after the possibly qualified table of such a slot,
         * written with {@code AS} or without it, except after {@code INTO},
         * which takes none. {@code UPDATE}'s own {@code SET} is never an alias,
         * but it does end one.
         */
        private void classifyQueryTables() {
            for (int i = 0; i < tokens.size(); i++) {
                String word = word(i);
                boolean inserts = "INTO".equals(word);
                boolean updates = "UPDATE".equals(word) && (i == 0 || ")".equals(word(i - 1)));

                if (
                    !inserts
                        && !updates
                        && !"FROM".equals(word)
                        && !"JOIN".equals(word)
                        && !"USING".equals(word)
                ) {
                    continue;
                }

                int table = "ONLY".equals(word(i + 1))
                    ? i + 2
                    : i + 1;

                if (table >= tokens.size()) {
                    continue;
                }

                boolean opensCall = !inserts && "(".equals(word(table + 1));

                if (isReservedCandidate(table) && !opensCall && !literalFollows(table)) {
                    names.add(table);
                }

                if (!opensCall && isNameToken(table)) {
                    classifyTableAlias(table, inserts, updates);
                }
            }
        }

        /** Whether a string literal follows the candidate at {@code index}. */
        private boolean literalFollows(int index) {
            String image = index + 1 < tokens.size()
                ? tokens.get(index + 1).image
                : "";

            return image.startsWith("'") || image.matches("(?i)E'.*");
        }

        /**
         * Classifies the alias of the table that begins at {@code table}, which
         * stands after that table's whole qualified name and before a token that
         * ends an operand.
         */
        private void classifyTableAlias(int table, boolean inserts, boolean updates) {
            if (inserts) {
                return;
            }

            int end = table;

            while (".".equals(word(end + 1)) && end + 2 < tokens.size()) {
                end += 2;
            }

            int alias = end + 1;

            if (alias >= tokens.size() || !isReservedCandidate(alias)) {
                return;
            }

            boolean set = updates && "SET".equals(word(alias));
            boolean endsAtSet = updates && "SET".equals(word(alias + 1));

            if (!set && (endsAtSet || OPERAND_FOLLOWERS.contains(word(alias + 1)))) {
                names.add(alias);
            }
        }

        /**
         * Classifies the names of one {@code CREATE}, {@code ALTER}, or
         * {@code DROP TABLE} statement: its own table, every other table it
         * names, the first word of each {@code CREATE TABLE} element, and the
         * column of each {@code ALTER TABLE} action. SQL that opens any other way
         * is classified as nothing, so it parses exactly as it does without this
         * pass.
         */
        private void classifyTableStatement() {
            String verb = word(0);
            int index = 1;

            while (TABLE_MODIFIERS.contains(word(index))) {
                index++;
            }

            if (!"TABLE".equals(word(index))) {
                return;
            }

            index++;

            if ("IF".equals(word(index)) && "NOT".equals(word(index + 1))) {
                index += 3;
            } else if ("IF".equals(word(index)) && "EXISTS".equals(word(index + 1))) {
                index += 2;
            }

            if ("ONLY".equals(word(index))) {
                index++;
            }

            classifyTable(index);

            int end = index;

            while (".".equals(word(end + 1))) {
                end += 2;
            }

            classifyFurtherTables(verb);

            if ("CREATE".equals(verb) && "(".equals(word(end + 1))) {
                classifyElements(end + 1);
            }

            if ("ALTER".equals(verb)) {
                classifyActions(end + 1);
            }
        }

        /**
         * Classifies every table a table statement names beside its own: a table
         * of a foreign key, of {@code LIKE}, of {@code PARTITION OF}, of
         * {@code INHERITS}, and of a {@code DROP TABLE} list.
         */
        private void classifyFurtherTables(String verb) {
            for (int i = 0; i < tokens.size(); i++) {
                String word = word(i);

                if (
                    "REFERENCES".equals(word)
                        || "LIKE".equals(word)
                        || "OF".equals(word) && "PARTITION".equals(word(i - 1))
                        || "DROP".equals(verb) && ",".equals(word)
                ) {
                    classifyTable(i + 1);
                }

                if ("INHERITS".equals(word) && ")".equals(word(i - 1)) && "(".equals(word(i + 1))) {
                    classifyInheritedTables(i + 2);
                }
            }
        }

        /** Classifies the tables of one {@code INHERITS (...)} list. */
        private void classifyInheritedTables(int index) {
            for (int i = index; i < tokens.size() && !")".equals(tokens.get(i).image); i++) {
                if (!",".equals(tokens.get(i).image)) {
                    classifyTable(i);
                }
            }
        }

        /**
         * Classifies the candidate at {@code index} as a table name. The
         * {@code IF} of an {@code IF [NOT] EXISTS} the opening words left is no
         * table name.
         */
        private void classifyTable(int index) {
            if (index >= tokens.size() || !isCandidate(index)) {
                return;
            }

            if (
                "IF".equals(word(index))
                    && ("NOT".equals(word(index + 1)) || "EXISTS".equals(word(index + 1)))
            ) {
                return;
            }

            names.add(index);
        }

        /**
         * Classifies the first word of each element of one {@code CREATE TABLE}
         * element list, which opens at {@code index}. The elements are split at
         * the commas that stand directly inside that list, so a comma of a column
         * list or of a type's parameters belongs to its element.
         */
        private void classifyElements(int index) {
            int depth = 0;

            for (int i = index; i < tokens.size(); i++) {
                String image = tokens.get(i).image;

                if ("(".equals(image)) {
                    depth++;

                    if (depth == 1) {
                        classifyElement(i + 1);
                    }
                } else if (")".equals(image)) {
                    depth--;

                    if (depth == 0) {
                        return;
                    }
                } else if (",".equals(image) && depth == 1) {
                    classifyElement(i + 1);
                }
            }
        }

        /**
         * Classifies the first word of one {@code CREATE TABLE} element as a
         * column name. An {@code EXCLUDE} before its own parentheses or before
         * {@code USING} opens a constraint instead.
         */
        private void classifyElement(int index) {
            slotStarts.add(index);

            if (index >= tokens.size() || !isCandidate(index) || opensExclusion(index)) {
                return;
            }

            names.add(index);
        }

        /** Whether the candidate at {@code index} opens an exclusion constraint. */
        private boolean opensExclusion(int index) {
            return "EXCLUDE".equals(word(index))
                && ("(".equals(word(index + 1)) || "USING".equals(word(index + 1)));
        }

        /**
         * Classifies the column of each top-level action of one
         * {@code ALTER TABLE}, whose actions open at {@code index}. The actions
         * are split at the commas that stand outside parentheses, as the schema
         * parser splits them.
         */
        private void classifyActions(int index) {
            int depth = 0;
            int start = index;

            for (int i = index; i <= tokens.size(); i++) {
                String image = i < tokens.size()
                    ? tokens.get(i).image
                    : ",";

                if ("(".equals(image)) {
                    depth++;
                } else if (")".equals(image)) {
                    depth--;
                } else if (",".equals(image) && depth == 0) {
                    classifyAction(start);

                    start = i + 1;
                }
            }
        }

        /**
         * Classifies the column one {@code ALTER TABLE} action names, which
         * follows {@code ADD [COLUMN] [IF NOT EXISTS]},
         * {@code DROP [COLUMN] [IF EXISTS]}, {@code ALTER [COLUMN]}, or
         * {@code RENAME [COLUMN]} on both sides of its {@code TO}. A
         * {@code RENAME TO} names a table and a {@code RENAME CONSTRAINT} names
         * no column at all.
         */
        private void classifyAction(int index) {
            slotStarts.add(index);

            String action = word(index);
            int column = index + 1;

            if ("RENAME".equals(action)) {
                classifyRename(column);

                return;
            }

            if (!"ADD".equals(action) && !"DROP".equals(action) && !"ALTER".equals(action)) {
                return;
            }

            if ("COLUMN".equals(word(column))) {
                column++;
            }

            if ("ADD".equals(action) && "IF".equals(word(column)) && "NOT".equals(word(column + 1))) {
                column += 3;
            } else if ("DROP".equals(action) && "IF".equals(word(column)) && "EXISTS".equals(word(column + 1))) {
                column += 2;
            }

            if (column >= tokens.size() || !isCandidate(column)) {
                return;
            }

            if ("ADD".equals(action) && opensExclusion(column)) {
                return;
            }

            names.add(column);
        }

        /** Classifies the names of one {@code RENAME} action. */
        private void classifyRename(int index) {
            if ("TO".equals(word(index))) {
                classifyTable(index + 1);

                return;
            }

            if ("CONSTRAINT".equals(word(index))) {
                return;
            }

            int column = "COLUMN".equals(word(index))
                ? index + 1
                : index;

            if (isCandidate(column)) {
                names.add(column);
            }

            if ("TO".equals(word(column + 1)) && isCandidate(column + 2)) {
                names.add(column + 2);
            }
        }

        /**
         * Classifies the candidates that stand for a name within an expression: a
         * part of a qualified name, an alias written with {@code AS}, and an
         * operand.
         */
        private void classifyExpressions() {
            Set<String> prefixes = tableStatement
                ? TABLE_OPERAND_PREFIXES
                : QUERY_OPERAND_PREFIXES;

            for (int i = 0; i < tokens.size(); i++) {
                if (!isCandidate(i)) {
                    continue;
                }

                if (".".equals(word(i - 1)) || ".".equals(word(i + 1))) {
                    names.add(i);

                    continue;
                }

                if (!tableStatement && isColumnAlias(i)) {
                    names.add(i);

                    continue;
                }

                if (isOperand(i, prefixes)) {
                    names.add(i);
                }
            }
        }

        /**
         * Whether the candidate at {@code index} is the alias of a select item,
         * which stands directly after {@code AS}. A candidate inside the
         * parentheses of a type-taking function states that type, and one
         * followed by its own parentheses opens a call.
         */
        private boolean isColumnAlias(int index) {
            return "AS".equals(word(index - 1))
                && !TYPE_TAKING_FUNCTIONS.contains(enclosingWords[index])
                && !"(".equals(word(index + 1));
        }

        /**
         * Whether the candidate at {@code index} is an operand: it follows an
         * expression opener that is itself no name, and a token that ends an
         * operand follows it. A query takes a reserved candidate only, and a
         * table statement every candidate that opens no element and no action.
         *
         * <p>A candidate after {@code NOT} is an operand only where that
         * {@code NOT} opens an expression of its own, which is where an
         * expression opener stands before it, as in {@code WHERE NOT} and
         * {@code AND NOT}. A {@code NOT} that negates the operator of an operand
         * already read, as the {@code NOT} of {@code IS NOT UNKNOWN} and of
         * {@code amount NOT BETWEEN -1 AND 1} does, is followed by a word of that
         * operator and not by a name. The first operand of {@code EXTRACT} states
         * the field to extract, so it is no name either.
         */
        private boolean isOperand(int index, Set<String> prefixes) {
            boolean takes = tableStatement
                ? !slotStarts.contains(index)
                : isReservedCandidate(index);

            if (!takes || names.contains(index - 1)) {
                return false;
            }

            String prefix = word(index - 1);

            if (!prefixes.contains(prefix) || !OPERAND_FOLLOWERS.contains(word(index + 1))) {
                return false;
            }

            if ("NOT".equals(prefix) && !prefixes.contains(word(index - 2))) {
                return false;
            }

            return !("(".equals(prefix) && "EXTRACT".equals(enclosingWords[index]));
        }

        /**
         * Whether the token at {@code index} is a candidate: one word the lexer
         * reports as a keyword and no word PostgreSQL reserves.
         */
        private boolean isCandidate(int index) {
            if (index < 0 || index >= tokens.size()) {
                return false;
            }

            Token token = tokens.get(index);

            return token.kind != CCJSqlParserConstants.S_IDENTIFIER
                && token.kind != CCJSqlParserConstants.S_QUOTED_IDENTIFIER
                && WORD.matcher(token.image).matches()
                && !POSTGRESQL_RESERVED.contains(token.image.toLowerCase(Locale.ROOT));
        }

        /** Whether the token at {@code index} is a candidate the parser reserves. */
        private boolean isReservedCandidate(int index) {
            return isCandidate(index)
                && !ParserKeywordsUtils.isNonReservedKeyword(tokens.get(index).kind);
        }

        /**
         * Whether the token at {@code index} can state a name at all, which an
         * identifier, a delimited identifier, and a candidate can.
         */
        private boolean isNameToken(int index) {
            if (index < 0 || index >= tokens.size()) {
                return false;
            }

            int kind = tokens.get(index).kind;

            return kind == CCJSqlParserConstants.S_IDENTIFIER
                || kind == CCJSqlParserConstants.S_QUOTED_IDENTIFIER
                || isCandidate(index);
        }

        /**
         * The upper-case image of the token at {@code index}, or the empty string
         * where the statement has no such token, which is the image the follower
         * sets state for the end of a statement.
         */
        private String word(int index) {
            return index < 0 || index >= tokens.size()
                ? ""
                : tokens.get(index).image.toUpperCase(Locale.ROOT);
        }

        /**
         * The word before the innermost parenthesis that encloses each token of
         * {@code tokens}, upper-cased, or the empty string where no parenthesis
         * does.
         */
        private static String[] enclosingWords(List<Token> tokens) {
            String[] enclosing = new String[tokens.size()];
            Deque<String> open = new ArrayDeque<>();

            for (int i = 0; i < tokens.size(); i++) {
                enclosing[i] = open.isEmpty()
                    ? ""
                    : open.peek();

                String image = tokens.get(i).image;

                if ("(".equals(image)) {
                    open.push(
                        i == 0
                            ? ""
                            : tokens.get(i - 1).image.toUpperCase(Locale.ROOT)
                    );
                } else if (")".equals(image) && !open.isEmpty()) {
                    open.pop();
                }
            }

            return enclosing;
        }
    }
}
