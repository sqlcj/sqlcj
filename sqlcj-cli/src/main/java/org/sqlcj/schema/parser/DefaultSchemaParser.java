package org.sqlcj.schema.parser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterDomain;
import net.sf.jsqlparser.statement.alter.AlterType;
import net.sf.jsqlparser.statement.create.domain.CreateDomain;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.type.CreateType;
import net.sf.jsqlparser.statement.create.type.EnumTypeDefinition;
import net.sf.jsqlparser.statement.drop.Drop;
import org.sqlcj.schema.DomainType;
import org.sqlcj.schema.EnumType;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.Table;
import org.sqlcj.schema.View;
import org.sqlcj.sql.KeywordNames;
import org.sqlcj.sql.SqlParseReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class DefaultSchemaParser implements SchemaParser {

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
     * One SQL identifier, delimited or not, as the lexer reports it. The letter
     * and digit classes are Unicode-aware, because PostgreSQL and the lexer
     * both read an unquoted identifier written with non-ASCII letters, such as
     * {@code café}, as one identifier.
     */
    static final Pattern IDENTIFIER = Pattern.compile(
        "[\\p{Alpha}_][\\p{Alnum}_$]*|\"[^\"]*\"",
        Pattern.UNICODE_CHARACTER_CLASS
    );

    /** Resolves the type a column declaration states. */
    final ColumnTypeMapping columnTypeMapping = new ColumnTypeMapping();

    /** Records the views and materialized views a schema source states. */
    final ViewParser viewParser = new ViewParser(this);

    /** Applies the enum type and domain statements of a schema source. */
    final EnumAndDomainParser enumAndDomainParser = new EnumAndDomainParser(this);

    /** Applies one {@code CREATE TABLE} and parses the table it states. */
    final CreateTableParser createTableParser = new CreateTableParser(this);

    /** Applies the {@code ALTER TABLE} and {@code DROP TABLE} statements. */
    final AlterTableParser alterTableParser = new AlterTableParser(this);

    /** The helpers the statement classes of the package share. */
    final SchemaParserSupport support = new SchemaParserSupport();

    /**
     * Applies one schema source to the schema the sources before it left, in
     * the one namespace that schema models, which the returned schema keeps.
     */
    @Override
    public Schema parse(Schema schema, String sql) {
        List<Table> tables = new ArrayList<>(schema.tables());
        List<EnumType> enums = new ArrayList<>(schema.enums());
        List<DomainType> domains = new ArrayList<>(schema.domains());
        List<View> views = new ArrayList<>(schema.views());

        SchemaNamespace namespace = new SchemaNamespace(schema.namespace());

        for (SchemaSourceSplitter.SourceStatement statement : SchemaSourceSplitter.split(sql)) {
            apply(statement, tables, enums, domains, views, namespace);
        }

        return new Schema(tables, enums, domains, views, schema.namespace());
    }

    /**
     * Parses one statement of a schema source alone and applies it, so that a
     * statement the parser cannot read fails only itself.
     *
     * <p>A statement the parser reads as exactly one statement of a kind of its
     * own is applied as any parsed statement is. Every other statement is
     * unreadable: the parser states a syntax failure for it, reports it only as
     * opaque text, or reads it as several statements. An unreadable statement is
     * ignored unless it opens as table or type DDL, which may change what the
     * schema models; such a statement is ignored as well when it names its own
     * table or type in a schema sqlcj does not model, and an unreadable
     * {@code ALTER TABLE} when every one of its top-level actions cannot change
     * a modeled column.
     *
     * <p>An unreadable {@code CREATE DOMAIN} and every {@code DROP DOMAIN},
     * which the parser never reads at all, are classified by their words
     * before the table and type openings, because both change the domains the
     * schema models.
     *
     * <p>A statement that creates a table named by a variable alone, or adds
     * constraints to one, is ignored before it is parsed at all; see
     * {@link #namesAVariableTable(List)}.
     *
     * <p>A view statement is classified by its words before it is parsed as
     * well, because the parser reads only some of them and none of them states
     * a column sqlcj models; see
     * {@link ViewParser#appliesViewStatement(SchemaSourceSplitter.SourceStatement,
     * List, List, List, List, SchemaNamespace)}.
     */
    private void apply(
        SchemaSourceSplitter.SourceStatement statement,
        List<Table> tables,
        List<EnumType> enums,
        List<DomainType> domains,
        List<View> views,
        SchemaNamespace namespace
    ) {
        if (namesAVariableTable(statement.tokens())) {
            return;
        }

        if (viewParser.appliesViewStatement(statement, tables, enums, domains, views, namespace)) {
            return;
        }

        Statements parsed = null;
        JSQLParserException failure = null;

        try {
            parsed = CCJSqlParserUtil.parseStatements(
                statement.sql(),
                keywordNames(statement.tokens(), statement.sql())
            );
        } catch (JSQLParserException e) {
            failure = e;
        }

        if (
            parsed != null
                && parsed.size() == 1
                && !(parsed.get(0) instanceof UnsupportedStatement)
        ) {
            apply(parsed.get(0), statement.line(), tables, enums, domains, views, namespace);

            return;
        }

        if (support.statesWords(statement.tokens(), 0, "CREATE", "DOMAIN")) {
            enumAndDomainParser.applyUnreadableCreateDomain(statement, failure, enums, domains, namespace);

            return;
        }

        if (support.statesWords(statement.tokens(), 0, "DROP", "DOMAIN")) {
            enumAndDomainParser.applyDropDomain(statement, domains, namespace);

            return;
        }

        String opening = tableOrTypeOpening(statement.tokens());

        if (
            opening == null
                || namesAnotherSchema(opening, statement.tokens(), namespace)
                || ignoresEveryAlterTableAction(opening, statement.tokens())
        ) {
            return;
        }

        if (failure != null) {
            throw unreadableStatement(statement, failure);
        }

        throw support.unsupportedOpening(opening, statement.line());
    }

    /**
     * The failure of an unreadable statement, stating the parser's reason at the
     * position of the unexpected token in the file. The parser reads one
     * statement at a time and therefore reports positions within it, so a
     * rejected statement alone is parsed a second time preceded by the line
     * breaks and spaces that stand before it in the file. A second parse that
     * unexpectedly succeeds leaves the first reason, whose column names a
     * position within the statement, at the statement's own line.
     *
     * <p>The padded text is classified for names itself, so the second parse
     * reads the same words as names as the first one did.
     */
    SchemaParseException unreadableStatement(
        SchemaSourceSplitter.SourceStatement statement,
        JSQLParserException failure
    ) {
        String padded = "\n".repeat(statement.line() - 1)
            + " ".repeat(statement.column() - 1)
            + statement.sql();

        JSQLParserException located = failure;

        try {
            CCJSqlParserUtil.parseStatements(padded, keywordNames(statement.tokens(), padded));
        } catch (JSQLParserException e) {
            located = e;
        }

        int line = SqlParseReason.lineOf(located);

        return new SchemaParseException(
            SqlParseReason.of(located, true),
            line > 0 ? line : statement.line(),
            located
        );
    }

    /**
     * The parser consumer that hands one statement's parser the words
     * PostgreSQL does not reserve as names; see {@link KeywordNames}.
     *
     * <p>Only a {@code CREATE}, {@code ALTER}, or {@code DROP TABLE} statement
     * states a table or a column name this way. Every other statement is parsed
     * exactly as it is without the pass, and is applied or ignored as it is
     * today, which the {@code null} the parser reads as no configuration at all
     * states.
     */
    private Consumer<CCJSqlParser> keywordNames(List<String> tokens, String sql) {
        String opening = tableOrTypeOpening(tokens);

        return opening != null && opening.endsWith("TABLE")
            ? KeywordNames.tableStatementNames(sql, null)
            : null;
    }

    /**
     * Reports whether an unreadable statement names its own table or type in a
     * schema sqlcj does not model, which is decided from the statement's tokens
     * alone: the qualifier before the dot of the name that follows the opening
     * words, the optional {@code IF [NOT] EXISTS}, and the optional
     * {@code ONLY}. Such a statement cannot change what the schema models and
     * is ignored, like a readable statement of the same name.
     *
     * <p>{@code DROP TABLE} is excluded, because it may name a modeled table
     * beside a table of another schema.
     */
    boolean namesAnotherSchema(
        String opening,
        List<String> tokens,
        SchemaNamespace namespace
    ) {
        if (opening.startsWith("DROP")) {
            return false;
        }

        int index = opening.split(" ").length;

        if (support.statesWords(tokens, index, "IF", "NOT", "EXISTS")) {
            index += 3;
        } else if (support.statesWords(tokens, index, "IF", "EXISTS")) {
            index += 2;
        }

        if (support.statesWords(tokens, index, "ONLY")) {
            index++;
        }

        return support.statesWords(tokens, index + 1, ".")
            && !namespace.isModeled(tokens.get(index));
    }

    /**
     * Reports whether an unreadable statement is an {@code ALTER TABLE} whose
     * every top-level action is ignored, which is decided from the statement's
     * tokens alone, without rewriting it and without resolving its table.
     *
     * <p>None of those actions changes a modeled column's existence, name, or
     * type, and PostgreSQL requires a column to be {@code NOT NULL} already
     * before {@code ADD GENERATED ... AS IDENTITY}. A key sqlcj cannot read
     * the columns of states none to mark non-null, so such a column keeps the
     * nullability its own declaration states. Without this rule no
     * {@code pg_dump} snapshot with an identity column loads, because the parser
     * reads neither the identity form {@code pg_dump} writes nor an
     * {@code EXCLUDE}, {@code DEFERRABLE INITIALLY DEFERRED},
     * {@code UNIQUE USING INDEX}, or {@code NOT VALID} constraint, nor an
     * unnamed {@code ADD CHECK}.
     */
    private boolean ignoresEveryAlterTableAction(String opening, List<String> tokens) {
        if (!"ALTER TABLE".equals(opening)) {
            return false;
        }

        int index = afterAlteredTableName(tokens);

        if (index < 0) {
            return false;
        }

        return topLevelActions(tokens.subList(index, tokens.size())).stream()
            .allMatch(this::ignoresAlterTableAction);
    }

    /**
     * The index of the first action token of an {@code ALTER TABLE}, after the
     * optional {@code IF EXISTS} and {@code ONLY} and the altered table's
     * possibly qualified name, or {@code -1} when the statement ends inside
     * that name.
     */
    private int afterAlteredTableName(List<String> tokens) {
        int index = 2;

        if (support.statesWords(tokens, index, "IF", "EXISTS")) {
            index += 2;
        }

        if (support.statesWords(tokens, index, "ONLY")) {
            index++;
        }

        if (index >= tokens.size()) {
            return -1;
        }

        index++;

        while (support.statesWords(tokens, index, ".")) {
            if (index + 1 >= tokens.size()) {
                return -1;
            }

            index += 2;
        }

        return index;
    }

    /**
     * The actions of one {@code ALTER TABLE}, split at the commas that stand
     * outside parentheses, so a comma of a column or parameter list belongs to
     * its action.
     */
    List<List<String>> topLevelActions(List<String> tokens) {
        List<List<String>> actions = new ArrayList<>();
        List<String> action = new ArrayList<>();
        int depth = 0;

        for (String token : tokens) {
            if ("(".equals(token)) {
                depth++;
            } else if (")".equals(token)) {
                depth--;
            } else if (",".equals(token) && depth == 0) {
                actions.add(action);
                action = new ArrayList<>();

                continue;
            }

            action.add(token);
        }

        actions.add(action);

        return actions;
    }

    /**
     * Reports whether one action of an unreadable {@code ALTER TABLE} begins as
     * an added constraint or as {@code ALTER [COLUMN] <name> ADD GENERATED}. The
     * words are compared case-insensitively, as PostgreSQL reads them.
     */
    private boolean ignoresAlterTableAction(List<String> action) {
        if (support.statesWords(action, 0, "ADD")) {
            return addsIgnoredConstraint(action);
        }

        if (!support.statesWords(action, 0, "ALTER")) {
            return false;
        }

        int index = support.statesWords(action, 1, "COLUMN")
            ? 2
            : 1;

        return index < action.size() && support.statesWords(action, index + 1, "ADD", "GENERATED");
    }

    private boolean addsIgnoredConstraint(List<String> action) {
        if (support.statesWords(action, 1, "CONSTRAINT")) {
            return action.size() > 2;
        }

        return support.statesWords(action, 1, "CHECK")
            || support.statesWords(action, 1, "UNIQUE")
            || support.statesWords(action, 1, "EXCLUDE")
            || support.statesWords(action, 1, "PRIMARY", "KEY")
            || support.statesWords(action, 1, "FOREIGN", "KEY");
    }

    /**
     * Reports whether a statement names its table by a variable alone, which
     * is decided from the statement's tokens alone, before the statement is
     * parsed: a {@code CREATE TABLE [IF NOT EXISTS] ${name} (}, or an
     * {@code ALTER TABLE [IF EXISTS] [ONLY] ${name}} whose every top-level
     * action adds a constraint. A variable is the token {@code $}, then an
     * opening brace, then one or more tokens, then the first closing brace, as
     * the lexer reports the name MyBatis Migrations substitutes. The words are
     * compared case-insensitively, as PostgreSQL reads them.
     *
     * <p>Both statements are ignored, because such a table is named nowhere
     * else in a schema source and neither statement can change a modeled
     * column. They are the two statements of the changelog script
     * {@code migrate init} writes, which without this rule does not load at
     * all: the parser rejects the {@code CREATE TABLE} at its opening brace and
     * reads the {@code ALTER TABLE} as an alteration of a table named
     * {@code $}, which fails resolving that table, so the rule must be decided
     * before the statement is parsed.
     */
    private boolean namesAVariableTable(List<String> tokens) {
        boolean creates = support.statesWords(tokens, 0, "CREATE", "TABLE");
        int index = 2;

        if (creates) {
            if (support.statesWords(tokens, index, "IF", "NOT", "EXISTS")) {
                index += 3;
            }
        } else if (support.statesWords(tokens, 0, "ALTER", "TABLE")) {
            if (support.statesWords(tokens, index, "IF", "EXISTS")) {
                index += 2;
            }

            if (support.statesWords(tokens, index, "ONLY")) {
                index++;
            }
        } else {
            return false;
        }

        if (!support.statesWords(tokens, index, "$", "{")) {
            return false;
        }

        int name = index + 2;
        int close = name;

        while (close < tokens.size() && !"}".equals(tokens.get(close))) {
            close++;
        }

        if (close == name || close == tokens.size()) {
            return false;
        }

        index = close + 1;

        if (creates) {
            return support.statesWords(tokens, index, "(");
        }

        return topLevelActions(tokens.subList(index, tokens.size())).stream()
            .allMatch(action -> support.statesWords(action, 0, "ADD") && addsIgnoredConstraint(action));
    }

    /**
     * Applies one statement to the tables and enum types the statements before
     * it left.
     *
     * <p>Only the statements sqlcj models are applied: {@code CREATE TABLE},
     * {@code ALTER TABLE}, {@code DROP TABLE},
     * {@code CREATE TYPE ... AS ENUM}, {@code ALTER TYPE},
     * {@code CREATE DOMAIN}, and {@code ALTER DOMAIN}. Every other
     * statement a schema source may record is ignored and is not resolved
     * against the schema at all.
     *
     * <p>A statement whose own table or type stands in a schema sqlcj does not
     * model is ignored the same way, before anything it names is resolved; see
     * {@link SchemaNamespace}. {@code DROP TABLE} is the one statement that
     * carries on, skipping such a name among its names.
     *
     * <p>An {@code ALTER TABLE} and an {@code ALTER TYPE} are applied to the
     * views recorded before them as well, because a {@code DROP NOT NULL} and
     * an enum type rename reach the views PostgreSQL holds; see
     * {@link ViewParser#markViewColumnsNullable(List)} and
     * {@link ViewParser#recordViewEnumRename(List, String, String)}.
     */
    private void apply(
        Statement statement,
        int line,
        List<Table> tables,
        List<EnumType> enums,
        List<DomainType> domains,
        List<View> views,
        SchemaNamespace namespace
    ) {
        if (statement instanceof CreateTable createTable) {
            if (namespace.isModeled(createTable.getTable())) {
                createTableParser.applyCreateTable(createTable, line, enums, domains, tables, namespace);
            }
        } else if (statement instanceof Alter alter) {
            if (namespace.isModeled(alter.getTable())) {
                alterTableParser.applyAlterTable(alter, line, enums, domains, tables, views, namespace);
            }
        } else if (statement instanceof Drop drop && drop.getObjectType() == Drop.ObjectType.TABLE) {
            alterTableParser.applyDropTable(drop, tables, namespace);
        } else if (
            statement instanceof CreateType createType
                && createType.getDefinition() instanceof EnumTypeDefinition definition
        ) {
            enumAndDomainParser.applyCreateEnumType(createType, definition, enums, domains, namespace);
        } else if (statement instanceof AlterType alterType) {
            enumAndDomainParser.applyAlterType(alterType, line, enums, domains, tables, views, namespace);
        } else if (statement instanceof CreateDomain createDomain) {
            enumAndDomainParser.applyCreateDomain(createDomain, enums, domains, namespace);
        } else if (statement instanceof AlterDomain alterDomain) {
            enumAndDomainParser.applyAlterDomain(alterDomain, domains, namespace);
        }
    }

    /**
     * The table or type DDL opening that {@code words} begin with, in upper case
     * and separated by single spaces, or {@code null} when they begin with
     * anything else. The words are compared case-insensitively, as PostgreSQL
     * reads them.
     *
     * <p>A table opening is {@code CREATE}, {@code ALTER}, or {@code DROP}, then
     * any of PostgreSQL's table modifiers, then {@code TABLE}. A type opening is
     * {@code CREATE} or {@code ALTER}, then {@code TYPE}. A statement sqlcj
     * cannot read is ignored unless it opens this way, because such a statement
     * may change the tables or the enum types the schema models.
     */
    private String tableOrTypeOpening(List<String> words) {
        if (words.isEmpty()) {
            return null;
        }

        String verb = support.upperCase(words.get(0));

        if (!"CREATE".equals(verb) && !"ALTER".equals(verb) && !"DROP".equals(verb)) {
            return null;
        }

        if (!"DROP".equals(verb) && words.size() > 1 && "TYPE".equals(support.upperCase(words.get(1)))) {
            return verb + " TYPE";
        }

        int index = 1;

        while (index < words.size() && TABLE_MODIFIERS.contains(support.upperCase(words.get(index)))) {
            index++;
        }

        if (index >= words.size() || !"TABLE".equals(support.upperCase(words.get(index)))) {
            return null;
        }

        return String.join(
            " ",
            words.subList(0, index + 1).stream()
                .map(support::upperCase)
                .toList()
        );
    }
}
