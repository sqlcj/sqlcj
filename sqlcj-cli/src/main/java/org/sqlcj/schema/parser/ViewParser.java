package org.sqlcj.schema.parser;

import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.CCJSqlParserTokenManager;
import net.sf.jsqlparser.parser.SimpleCharStream;
import net.sf.jsqlparser.parser.StringProvider;
import net.sf.jsqlparser.parser.Token;
import org.sqlcj.schema.DomainType;
import org.sqlcj.schema.EnumType;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.Table;
import org.sqlcj.schema.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the views and materialized views one schema source states, from the
 * words of its {@code CREATE} and {@code DROP} statements alone, with the
 * defining query, the declared column list, and the schema each view's columns
 * are fixed against, and keeps the recorded views in step with a dropped
 * {@code NOT NULL} and a renamed enum type. It reads the parser it belongs to
 * for the word matching, the lookups, and the identifier pattern the package
 * shares.
 */
final class ViewParser {

    /**
     * The clauses PostgreSQL writes after the defining query of a view or a
     * materialized view, which state nothing about the query's columns and are
     * no part of the recorded query text.
     */
    private static final List<String[]> TRAILING_VIEW_CLAUSES = List.of(
        new String[] { "WITH", "CHECK", "OPTION" },
        new String[] { "WITH", "CASCADED", "CHECK", "OPTION" },
        new String[] { "WITH", "LOCAL", "CHECK", "OPTION" },
        new String[] { "WITH", "DATA" },
        new String[] { "WITH", "NO", "DATA" }
    );

    /** The parser these views are recorded for. */
    private final DefaultSchemaParser parser;

    ViewParser(DefaultSchemaParser parser) {
        this.parser = parser;
    }

    /**
     * Records or removes the views one statement states, and reports whether
     * its words state such a statement at all. The views sqlcj records are the
     * views and materialized views of the modeled namespace, from the words of
     * {@code CREATE [OR REPLACE] [RECURSIVE] VIEW},
     * {@code CREATE MATERIALIZED VIEW [IF NOT EXISTS]}, and
     * {@code DROP [MATERIALIZED] VIEW [IF EXISTS]}. The words are compared
     * case-insensitively, as PostgreSQL reads them.
     *
     * <p>They are classified from the statement's words alone, because the
     * parser reads only some view statements and none of them states a column
     * sqlcj models in the schema: a view's columns stand in its own query,
     * which the analyzer resolves when a query names the view, against the
     * schema recorded with it.
     *
     * <p>Words outside these forms state no view statement and are left to the
     * statement rules that follow, so a temporary view, a view of a schema
     * sqlcj does not model, and every other form stay ignored and unrecorded.
     * No form is ever rejected here, so every schema history that loads keeps
     * loading: a {@code DROP} of an unrecorded name changes nothing.
     */
    boolean appliesViewStatement(
        SchemaSourceSplitter.SourceStatement statement,
        List<Table> tables,
        List<EnumType> enums,
        List<DomainType> domains,
        List<View> views,
        SchemaNamespace namespace
    ) {
        List<String> tokens = statement.tokens();

        if (parser.support.statesWords(tokens, 0, "CREATE")) {
            return appliesCreateView(statement, tables, enums, domains, views, namespace);
        }

        if (!parser.support.statesWords(tokens, 0, "DROP")) {
            return false;
        }

        int index = parser.support.statesWords(tokens, 1, "MATERIALIZED")
            ? 2
            : 1;

        if (!parser.support.statesWords(tokens, index, "VIEW")) {
            return false;
        }

        List<String> names = parser.support.droppedNames(tokens, index + 1);

        if (names == null) {
            return false;
        }

        for (String declaredName : names) {
            String viewName = namespace.modeledTypeName(declaredName);

            if (viewName != null) {
                removeView(views, viewName);
            }
        }

        return true;
    }

    /**
     * Records the view one {@code CREATE} states, and reports whether its words
     * state such a statement at all. Words that state no name after the view
     * words record nothing and are left to the statement rules that follow.
     *
     * <p>The recorded definition is the view's kind, the text of its defining
     * query, the column list written after its name, and the schema as it stood
     * just before this statement, which is the schema PostgreSQL fixes the
     * view's columns against.
     */
    private boolean appliesCreateView(
        SchemaSourceSplitter.SourceStatement statement,
        List<Table> tables,
        List<EnumType> enums,
        List<DomainType> domains,
        List<View> views,
        SchemaNamespace namespace
    ) {
        List<String> tokens = statement.tokens();
        int index = 1;
        boolean materialized = parser.support.statesWords(tokens, index, "MATERIALIZED");
        boolean recursive = false;

        if (materialized) {
            index++;
        } else {
            if (parser.support.statesWords(tokens, index, "OR", "REPLACE")) {
                index += 2;
            }

            if (parser.support.statesWords(tokens, index, "RECURSIVE")) {
                recursive = true;
                index++;
            }
        }

        if (!parser.support.statesWords(tokens, index, "VIEW")) {
            return false;
        }

        index++;

        boolean ifNotExists = materialized && parser.support.statesWords(tokens, index, "IF", "NOT", "EXISTS");

        if (ifNotExists) {
            index += 3;
        }

        int nameEnd = parser.support.afterTypeName(tokens, index);

        if (nameEnd < 0) {
            return false;
        }

        String viewName = namespace.modeledTypeName(parser.support.declaredName(tokens, index, nameEnd));

        if (viewName != null) {
            addView(
                new View(
                    viewName,
                    materialized,
                    definingQuery(statement.sql()),
                    columnListNames(tokens, nameEnd),
                    new Schema(tables, enums, domains, views, namespace.name()),
                    recursive
                ),
                views,
                ifNotExists
            );
        }

        return true;
    }

    /**
     * The text of the defining query one {@code CREATE} of a view states: the
     * text after the {@code AS} that follows the view's name, its optional
     * column list, and the optional clauses PostgreSQL places between them —
     * a view's {@code WITH (...)} and a materialized view's {@code USING},
     * {@code WITH (...)}, and {@code TABLESPACE} — without the trailing
     * {@code WITH [CASCADED | LOCAL] CHECK OPTION} or {@code WITH [NO] DATA}
     * clause, which states nothing about the query's columns, and without the
     * statement separator.
     *
     * <p>The {@code AS} is the first one written outside parentheses, because
     * every clause before it writes its own values inside them. A statement
     * that writes no such {@code AS} states no query at all, which makes the
     * view one sqlcj cannot analyze where a query names it.
     */
    private String definingQuery(String sql) {
        List<int[]> spans = tokenSpans(sql);
        int depth = 0;
        int queryStart = -1;

        for (int index = 0; index < spans.size(); index++) {
            String image = image(sql, spans.get(index));

            if ("(".equals(image)) {
                depth++;
            } else if (")".equals(image)) {
                depth--;
            } else if (depth == 0 && "AS".equalsIgnoreCase(image)) {
                queryStart = spans.get(index)[1];

                break;
            }
        }

        if (queryStart < 0) {
            return "";
        }

        return sql.substring(queryStart, queryEnd(sql, spans)).strip();
    }

    /**
     * The offset the defining query of a view ends at: the beginning of its
     * trailing {@code WITH CHECK OPTION} or {@code WITH [NO] DATA} clause, and
     * the end of the statement's last token otherwise, so that the separator
     * and any comment after it are no part of the query.
     */
    private int queryEnd(String sql, List<int[]> spans) {
        List<String> images = new ArrayList<>();

        for (int[] span : spans) {
            images.add(image(sql, span));
        }

        int end = images.size();

        if (end > 0 && ";".equals(images.get(end - 1))) {
            end--;
        }

        for (String[] clause : TRAILING_VIEW_CLAUSES) {
            int from = end - clause.length;

            if (from >= 0 && parser.support.statesWords(images.subList(0, end), from, clause)) {
                return spans.get(from)[0];
            }
        }

        return end == 0
            ? 0
            : spans.get(end - 1)[1];
    }

    /**
     * The begin and end offset of each token of one statement, lexed with the
     * SQL parser's own token manager, so the text between two tokens is the
     * text the statement writes there, comments and whitespace included. The
     * statement separator is a token of its own here, which
     * {@link #queryEnd(String, List)} excludes from the query.
     */
    private List<int[]> tokenSpans(String sql) {
        SimpleCharStream stream = new SimpleCharStream(new StringProvider(sql), 1, 1);
        CCJSqlParserTokenManager tokenManager = new CCJSqlParserTokenManager(stream);
        List<int[]> spans = new ArrayList<>();

        while (true) {
            Token token = tokenManager.getNextToken();

            if (token.kind == CCJSqlParserConstants.EOF) {
                return spans;
            }

            int begin = stream.getAbsoluteTokenBegin() - 1;

            spans.add(new int[] { begin, begin + token.image.length() });
        }
    }

    private String image(String sql, int[] span) {
        return sql.substring(span[0], span[1]);
    }

    /**
     * The declared names of the column list one {@code CREATE} of a view
     * writes after the view's name, as the statement writes them, and no name
     * at all where it writes no list or writes one PostgreSQL does not accept,
     * which is a list of plain column names.
     */
    private List<String> columnListNames(List<String> tokens, int nameEnd) {
        if (!parser.support.statesWords(tokens, nameEnd, "(")) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        int index = nameEnd + 1;

        while (true) {
            if (index >= tokens.size() || !DefaultSchemaParser.IDENTIFIER.matcher(tokens.get(index)).matches()) {
                return List.of();
            }

            names.add(tokens.get(index));

            index++;

            if (parser.support.statesWords(tokens, index, ")")) {
                return List.copyOf(names);
            }

            if (!parser.support.statesWords(tokens, index, ",")) {
                return List.of();
            }

            index++;
        }
    }

    /**
     * Appends one view, and replaces a recorded view of the same name in its
     * position, because PostgreSQL holds one relation of a name at a time. A
     * {@code CREATE MATERIALIZED VIEW IF NOT EXISTS} of a recorded name keeps
     * the recorded view, as PostgreSQL keeps the relation it has.
     */
    private void addView(View view, List<View> views, boolean ifNotExists) {
        int index = indexOfView(views, view.name());

        if (index < 0) {
            views.add(view);

            return;
        }

        if (ifNotExists) {
            return;
        }

        views.set(index, view);
    }

    /**
     * Removes the recorded view of {@code viewName}, of either kind, if the
     * schema has one.
     */
    private void removeView(List<View> views, String viewName) {
        int index = indexOfView(views, viewName);

        if (index >= 0) {
            views.remove(index);
        }
    }

    /** View names are matched case-insensitively, as a query names them. */
    private int indexOfView(List<View> views, String viewName) {
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).name().equalsIgnoreCase(viewName)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Marks the columns of every recorded view nullable, keeping each view's
     * recorded schema as it stood, because a column PostgreSQL made nullable
     * may be read by any of their columns and sqlcj does not track which table
     * column a view column reads.
     */
    void markViewColumnsNullable(List<View> views) {
        views.replaceAll(
            view -> new View(
                view.name(),
                view.materialized(),
                view.query(),
                view.columnNames(),
                view.schema(),
                view.recursive(),
                true,
                view.enumRenames()
            )
        );
    }

    /**
     * Records one enum type rename with every recorded view, after the renames
     * recorded before it, keeping each view's recorded schema as it stood,
     * because a defining query may name the type by the name it had there.
     */
    void recordViewEnumRename(List<View> views, String typeName, String newName) {
        views.replaceAll(view -> {
            List<View.EnumRename> renames = new ArrayList<>(view.enumRenames());

            renames.add(new View.EnumRename(typeName, newName));

            return new View(
                view.name(),
                view.materialized(),
                view.query(),
                view.columnNames(),
                view.schema(),
                view.recursive(),
                view.columnsNullable(),
                renames
            );
        });
    }
}
