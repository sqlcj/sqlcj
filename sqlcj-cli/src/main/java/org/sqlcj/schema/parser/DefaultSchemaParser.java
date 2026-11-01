package org.sqlcj.schema.parser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.statement.LikeClause;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterDomain;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterExpressionPartition;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.alter.AlterType;
import net.sf.jsqlparser.statement.create.domain.CreateDomain;
import net.sf.jsqlparser.statement.create.table.CheckConstraint;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ExcludeConstraint;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.create.table.TableElement;
import net.sf.jsqlparser.statement.create.type.CreateType;
import net.sf.jsqlparser.statement.create.type.EnumTypeDefinition;
import net.sf.jsqlparser.statement.drop.Drop;
import org.sqlcj.diagnostic.Suggestions;
import org.sqlcj.schema.Column;
import org.sqlcj.schema.Constraint;
import org.sqlcj.schema.ConstraintType;
import org.sqlcj.schema.DomainType;
import org.sqlcj.schema.EnumType;
import org.sqlcj.schema.Schema;
import org.sqlcj.schema.Table;
import org.sqlcj.schema.View;
import org.sqlcj.sql.SqlParseReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final Pattern IDENTIFIER = Pattern.compile(
        "[\\p{Alpha}_][\\p{Alnum}_$]*|\"[^\"]*\"",
        Pattern.UNICODE_CHARACTER_CLASS
    );

    /** Resolves the type a column declaration states. */
    private final ColumnTypeMapping columnTypeMapping = new ColumnTypeMapping();

    @Override
    public Schema parse(Schema schema, String sql) {
        List<Table> tables = new ArrayList<>(schema.tables());
        List<EnumType> enums = new ArrayList<>(schema.enums());
        List<DomainType> domains = new ArrayList<>(schema.domains());
        List<View> views = new ArrayList<>(schema.views());

        for (SchemaSourceSplitter.SourceStatement statement : SchemaSourceSplitter.split(sql)) {
            apply(statement, tables, enums, domains, views);
        }

        return new Schema(tables, enums, domains, views);
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
     * a column sqlcj models; see {@link #appliesViewStatement(List, List)}.
     */
    private void apply(
        SchemaSourceSplitter.SourceStatement statement,
        List<Table> tables,
        List<EnumType> enums,
        List<DomainType> domains,
        List<View> views
    ) {
        if (namesAVariableTable(statement.tokens())) {
            return;
        }

        if (appliesViewStatement(statement.tokens(), views)) {
            return;
        }

        Statements parsed = null;
        JSQLParserException failure = null;

        try {
            parsed = CCJSqlParserUtil.parseStatements(statement.sql());
        } catch (JSQLParserException e) {
            failure = e;
        }

        if (
            parsed != null
                && parsed.size() == 1
                && !(parsed.get(0) instanceof UnsupportedStatement)
        ) {
            apply(parsed.get(0), statement.line(), tables, enums, domains);

            return;
        }

        if (statesWords(statement.tokens(), 0, "CREATE", "DOMAIN")) {
            applyUnreadableCreateDomain(statement, failure, enums, domains);

            return;
        }

        if (statesWords(statement.tokens(), 0, "DROP", "DOMAIN")) {
            applyDropDomain(statement, domains);

            return;
        }

        String opening = tableOrTypeOpening(statement.tokens());

        if (
            opening == null
                || namesAnotherSchema(opening, statement.tokens())
                || ignoresEveryAlterTableAction(opening, statement.tokens())
        ) {
            return;
        }

        if (failure != null) {
            throw unreadableStatement(statement, failure);
        }

        throw unsupportedOpening(opening, statement.line());
    }

    /**
     * The failure of an unreadable statement, stating the parser's reason at the
     * position of the unexpected token in the file. The parser reads one
     * statement at a time and therefore reports positions within it, so a
     * rejected statement alone is parsed a second time preceded by the line
     * breaks and spaces that stand before it in the file. A second parse that
     * unexpectedly succeeds leaves the first reason, whose column names a
     * position within the statement, at the statement's own line.
     */
    private SchemaParseException unreadableStatement(
        SchemaSourceSplitter.SourceStatement statement,
        JSQLParserException failure
    ) {
        String padded = "\n".repeat(statement.line() - 1)
            + " ".repeat(statement.column() - 1)
            + statement.sql();

        JSQLParserException located = failure;

        try {
            CCJSqlParserUtil.parseStatements(padded);
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
    private boolean namesAnotherSchema(String opening, List<String> tokens) {
        if (opening.startsWith("DROP")) {
            return false;
        }

        int index = opening.split(" ").length;

        if (statesWords(tokens, index, "IF", "NOT", "EXISTS")) {
            index += 3;
        } else if (statesWords(tokens, index, "IF", "EXISTS")) {
            index += 2;
        }

        if (statesWords(tokens, index, "ONLY")) {
            index++;
        }

        return statesWords(tokens, index + 1, ".")
            && !SchemaNamespace.isModeled(tokens.get(index));
    }

    /**
     * Reports whether an unreadable statement is an {@code ALTER TABLE} whose
     * every top-level action is ignored, which is decided from the statement's
     * tokens alone, without rewriting it and without resolving its table.
     *
     * <p>None of those actions can change a modeled column's existence, name,
     * type, or nullability: nullability comes from {@code NOT NULL} alone, and
     * PostgreSQL requires a column to be {@code NOT NULL} already before
     * {@code ADD GENERATED ... AS IDENTITY}. Without this rule no
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

        if (statesWords(tokens, index, "IF", "EXISTS")) {
            index += 2;
        }

        if (statesWords(tokens, index, "ONLY")) {
            index++;
        }

        if (index >= tokens.size()) {
            return -1;
        }

        index++;

        while (statesWords(tokens, index, ".")) {
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
    private List<List<String>> topLevelActions(List<String> tokens) {
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
        if (statesWords(action, 0, "ADD")) {
            return addsIgnoredConstraint(action);
        }

        if (!statesWords(action, 0, "ALTER")) {
            return false;
        }

        int index = statesWords(action, 1, "COLUMN")
            ? 2
            : 1;

        return index < action.size() && statesWords(action, index + 1, "ADD", "GENERATED");
    }

    private boolean addsIgnoredConstraint(List<String> action) {
        if (statesWords(action, 1, "CONSTRAINT")) {
            return action.size() > 2;
        }

        return statesWords(action, 1, "CHECK")
            || statesWords(action, 1, "UNIQUE")
            || statesWords(action, 1, "EXCLUDE")
            || statesWords(action, 1, "PRIMARY", "KEY")
            || statesWords(action, 1, "FOREIGN", "KEY");
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
        boolean creates = statesWords(tokens, 0, "CREATE", "TABLE");
        int index = 2;

        if (creates) {
            if (statesWords(tokens, index, "IF", "NOT", "EXISTS")) {
                index += 3;
            }
        } else if (statesWords(tokens, 0, "ALTER", "TABLE")) {
            if (statesWords(tokens, index, "IF", "EXISTS")) {
                index += 2;
            }

            if (statesWords(tokens, index, "ONLY")) {
                index++;
            }
        } else {
            return false;
        }

        if (!statesWords(tokens, index, "$", "{")) {
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
            return statesWords(tokens, index, "(");
        }

        return topLevelActions(tokens.subList(index, tokens.size())).stream()
            .allMatch(action -> statesWords(action, 0, "ADD") && addsIgnoredConstraint(action));
    }

    /**
     * Records or removes the views one statement states, and reports whether
     * its words state such a statement at all. The views sqlcj records are the
     * views and materialized views of the modeled namespace, by name and kind
     * alone, from the words of
     * {@code CREATE [OR REPLACE] [RECURSIVE] VIEW},
     * {@code CREATE MATERIALIZED VIEW [IF NOT EXISTS]}, and
     * {@code DROP [MATERIALIZED] VIEW [IF EXISTS]}. The words are compared
     * case-insensitively, as PostgreSQL reads them.
     *
     * <p>They are classified from the statement's words alone, because the
     * parser reads only some view statements and none of them is applied to the
     * schema model: a view's columns stand in its query, which sqlcj does not
     * analyze there, so a query that names a view states so instead.
     *
     * <p>Words outside these forms state no view statement and are left to the
     * statement rules that follow, so a temporary view, a view of a schema
     * sqlcj does not model, and every other form stay ignored and unrecorded.
     * No form is ever rejected here, so every schema history that loads keeps
     * loading: a {@code DROP} of an unrecorded name changes nothing.
     */
    private boolean appliesViewStatement(List<String> tokens, List<View> views) {
        if (statesWords(tokens, 0, "CREATE")) {
            return appliesCreateView(tokens, views);
        }

        if (!statesWords(tokens, 0, "DROP")) {
            return false;
        }

        int index = statesWords(tokens, 1, "MATERIALIZED")
            ? 2
            : 1;

        if (!statesWords(tokens, index, "VIEW")) {
            return false;
        }

        List<String> names = droppedNames(tokens, index + 1);

        if (names == null) {
            return false;
        }

        for (String declaredName : names) {
            String viewName = SchemaNamespace.modeledTypeName(declaredName);

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
     */
    private boolean appliesCreateView(List<String> tokens, List<View> views) {
        int index = 1;
        boolean materialized = statesWords(tokens, index, "MATERIALIZED");

        if (materialized) {
            index++;
        } else {
            if (statesWords(tokens, index, "OR", "REPLACE")) {
                index += 2;
            }

            if (statesWords(tokens, index, "RECURSIVE")) {
                index++;
            }
        }

        if (!statesWords(tokens, index, "VIEW")) {
            return false;
        }

        index++;

        if (materialized && statesWords(tokens, index, "IF", "NOT", "EXISTS")) {
            index += 3;
        }

        int nameEnd = afterTypeName(tokens, index);

        if (nameEnd < 0) {
            return false;
        }

        String viewName = SchemaNamespace.modeledTypeName(declaredName(tokens, index, nameEnd));

        if (viewName != null) {
            addView(new View(viewName, materialized), views);
        }

        return true;
    }

    /**
     * Appends one view, and replaces a recorded view of the same name in its
     * position, because PostgreSQL holds one relation of a name at a time.
     */
    private void addView(View view, List<View> views) {
        int index = indexOfView(views, view.name());

        if (index < 0) {
            views.add(view);

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
     * Reports whether the tokens from {@code index} on are {@code words},
     * compared case-insensitively. A list the parser did not state at all states
     * no words.
     */
    private boolean statesWords(List<String> tokens, int index, String... words) {
        if (tokens == null || index + words.length > tokens.size()) {
            return false;
        }

        for (int i = 0; i < words.length; i++) {
            if (!words[i].equalsIgnoreCase(tokens.get(index + i))) {
                return false;
            }
        }

        return true;
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
     */
    private void apply(
        Statement statement,
        int line,
        List<Table> tables,
        List<EnumType> enums,
        List<DomainType> domains
    ) {
        if (statement instanceof CreateTable createTable) {
            if (SchemaNamespace.isModeled(createTable.getTable())) {
                applyCreateTable(createTable, line, enums, domains, tables);
            }
        } else if (statement instanceof Alter alter) {
            if (SchemaNamespace.isModeled(alter.getTable())) {
                applyAlterTable(alter, line, enums, domains, tables);
            }
        } else if (statement instanceof Drop drop && drop.getObjectType() == Drop.ObjectType.TABLE) {
            applyDropTable(drop, tables);
        } else if (
            statement instanceof CreateType createType
                && createType.getDefinition() instanceof EnumTypeDefinition definition
        ) {
            applyCreateEnumType(createType, definition, enums, domains);
        } else if (statement instanceof AlterType alterType) {
            applyAlterType(alterType, line, enums, domains, tables);
        } else if (statement instanceof CreateDomain createDomain) {
            applyCreateDomain(createDomain, enums, domains);
        } else if (statement instanceof AlterDomain alterDomain) {
            applyAlterDomain(alterDomain, domains);
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

        String verb = upperCase(words.get(0));

        if (!"CREATE".equals(verb) && !"ALTER".equals(verb) && !"DROP".equals(verb)) {
            return null;
        }

        if (!"DROP".equals(verb) && words.size() > 1 && "TYPE".equals(upperCase(words.get(1)))) {
            return verb + " TYPE";
        }

        int index = 1;

        while (index < words.size() && TABLE_MODIFIERS.contains(upperCase(words.get(index)))) {
            index++;
        }

        if (index >= words.size() || !"TABLE".equals(upperCase(words.get(index)))) {
            return null;
        }

        return String.join(
            " ",
            words.subList(0, index + 1).stream()
                .map(this::upperCase)
                .toList()
        );
    }

    private String upperCase(String word) {
        return word.toUpperCase(Locale.ROOT);
    }

    /**
     * Appends one enum type with its declared labels, and replaces a modeled
     * enum of the same name in its position, because the {@code DROP TYPE} that
     * freed the name is never seen. A repeated label fails, as PostgreSQL
     * rejects it. An enum is the only kind of {@code CREATE TYPE} sqlcj
     * models; one of any other kind is ignored, and a column of such a type is
     * recorded with its declared type. A type of a schema sqlcj does not model
     * is ignored as well.
     *
     * <p>A modeled domain of the name is removed, because PostgreSQL holds one
     * type of a name at a time, so the declared enum takes the name over.
     */
    private void applyCreateEnumType(
        CreateType createType,
        EnumTypeDefinition definition,
        List<EnumType> enums,
        List<DomainType> domains
    ) {
        String typeName = SchemaNamespace.modeledTypeName(createType.getName());

        if (typeName == null) {
            return;
        }

        removeDomain(domains, typeName);

        int index = indexOfEnum(enums, typeName);

        List<String> labels = new ArrayList<>();

        for (StringValue declaredLabel : definition.getLabels()) {
            String label = declaredLabel.getNotExcapedValue();

            if (labels.contains(label)) {
                throw labelAlreadyExists(typeName, label);
            }

            labels.add(label);
        }

        EnumType enumType = new EnumType(typeName, labels);

        if (index < 0) {
            enums.add(enumType);

            return;
        }

        enums.set(index, enumType);
    }

    /**
     * Applies one {@code ALTER TYPE} action, resolving the stated type name in
     * the one namespace sqlcj models, so a {@code public}-qualified name
     * resolves as its unqualified name and a type of any other schema is
     * ignored.
     *
     * <p>An action that cannot change a modeled enum is ignored without the
     * type being resolved at all: ownership and the attribute actions of a
     * composite type. Every other action sqlcj models resolves the type, and an
     * action sqlcj does not model is rejected naming the action.
     *
     * <p>{@code RENAME TO} is the one action a domain shares with an enum
     * type, because PostgreSQL renames a domain through {@code ALTER TYPE} as
     * well; every other action names an enum type.
     */
    private void applyAlterType(
        AlterType alterType,
        int line,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables
    ) {
        AlterType.Action action = alterType.getAction();

        if (ignoresAlterTypeAction(action)) {
            return;
        }

        String typeName = SchemaNamespace.modeledTypeName(alterType.getName());

        if (typeName == null) {
            return;
        }

        if (action == AlterType.Action.ADD_VALUE) {
            applyAddEnumValue(alterType, typeName, enums);

            return;
        }

        int index = indexOfEnum(enums, typeName);

        switch (action) {
            case RENAME_VALUE -> renameEnumLabel(alterType, typeName, index, enums);
            case RENAME -> renameType(
                MultiPartName.unquote(alterType.getNewName()),
                typeName,
                index,
                enums,
                domains,
                tables
            );
            case SET_SCHEMA -> moveEnumTypeToSchema(alterType, line, index);
            default -> throw unsupportedAlterTypeAction(String.valueOf(action), line);
        }
    }

    /**
     * Reports whether one {@code ALTER TYPE} action cannot change a modeled
     * enum type: {@code OWNER TO} and the attribute actions, which belong to a
     * composite type.
     */
    private boolean ignoresAlterTypeAction(AlterType.Action action) {
        return switch (action) {
            case OWNER, RENAME_ATTRIBUTE, ATTRIBUTES -> true;
            default -> false;
        };
    }

    /**
     * Renames one label of an enum type in its position, so the modeled labels
     * keep PostgreSQL's sort order. A missing old label and a new label the
     * type already has both fail, as PostgreSQL rejects them.
     */
    private void renameEnumLabel(
        AlterType alterType,
        String typeName,
        int index,
        List<EnumType> enums
    ) {
        if (index < 0) {
            throw typeNotFound(typeName);
        }

        EnumType enumType = enums.get(index);
        String label = alterType.getValue().getNotExcapedValue();
        String newLabel = alterType.getNewValue().getNotExcapedValue();

        int position = enumType.labels().indexOf(label);

        if (position < 0) {
            throw labelNotFound(typeName, label);
        }

        if (enumType.labels().contains(newLabel)) {
            throw labelAlreadyExists(typeName, newLabel);
        }

        List<String> labels = new ArrayList<>(enumType.labels());

        labels.set(position, newLabel);

        enums.set(index, new EnumType(enumType.name(), labels));
    }

    /**
     * Renames the one modeled type of {@code typeName}: a modeled domain of
     * that name, which {@code ALTER TYPE} and {@code ALTER DOMAIN} rename
     * alike, and otherwise the modeled enum type at {@code index}.
     */
    private void renameType(
        String newName,
        String typeName,
        int index,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables
    ) {
        int domain = indexOfDomain(domains, typeName);

        if (domain >= 0) {
            renameDomain(newName, domain, domains);

            return;
        }

        renameEnumType(newName, typeName, index, enums, domains, tables);
    }

    /**
     * Renames one enum type in its position, keeping its labels, and renames
     * the type of every column of it, scalar and array alike, because a column
     * names its enum type by that name, and of every domain over it. A modeled
     * enum or domain of the new name is removed, because the
     * {@code DROP TYPE} that freed the name is never seen; a column still
     * naming the renamed enum keeps that name, so it is modeled as a column of
     * the renamed enum with its labels, the column PostgreSQL would have
     * removed.
     */
    private void renameEnumType(
        String newName,
        String typeName,
        int index,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables
    ) {
        if (index < 0) {
            return;
        }

        int replaced = indexOfEnum(enums, newName);
        int renamed = index;

        if (replaced >= 0 && replaced != index) {
            enums.remove(replaced);

            if (replaced < index) {
                renamed--;
            }
        }

        enums.set(renamed, new EnumType(newName, enums.get(renamed).labels()));

        tables.replaceAll(table -> renameColumnEnumType(table, typeName, newName));

        removeDomain(domains, newName);

        domains.replaceAll(domain -> renameDomainEnumType(domain, typeName, newName));
    }

    /**
     * Rejects {@code SET SCHEMA} of a modeled enum type, because its columns
     * would keep the type in a namespace sqlcj does not model. A move into the
     * one namespace it models leaves the type where it already is, and a type
     * sqlcj does not model is ignored.
     */
    private void moveEnumTypeToSchema(AlterType alterType, int line, int index) {
        if (index < 0 || SchemaNamespace.isModeled(alterType.getNewName())) {
            return;
        }

        throw unsupportedAlterTypeAction("SET SCHEMA " + alterType.getNewName(), line);
    }

    /** Renames the enum type of every column of {@code oldName}. */
    private Table renameColumnEnumType(Table table, String oldName, String newName) {
        List<Column> columns = new ArrayList<>(table.columns());

        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);

            if (column.enumType() == null || !column.enumType().equalsIgnoreCase(oldName)) {
                continue;
            }

            columns.set(
                i,
                new Column(
                    column.name(),
                    column.type(),
                    column.nullable(),
                    column.unsupportedType(),
                    newName,
                    column.array(),
                    column.blankPadded()
                )
            );
        }

        return new Table(table.name(), columns, table.constraints(), table.partitionOf());
    }

    /**
     * Inserts one label into an enum type: after the last label by default, and
     * otherwise directly before or after the stated neighbor, so the modeled
     * labels stay in PostgreSQL's sort order.
     *
     * <p>As PostgreSQL does, {@code IF NOT EXISTS} is decided by the label
     * alone: a label the type already has is a no-op even when the stated
     * neighbor is not one of its labels.
     */
    private void applyAddEnumValue(AlterType alterType, String typeName, List<EnumType> enums) {
        int index = indexOfEnum(enums, typeName);

        if (index < 0) {
            throw typeNotFound(typeName);
        }

        EnumType enumType = enums.get(index);
        String label = alterType.getValue().getNotExcapedValue();

        if (enumType.labels().contains(label)) {
            if (alterType.isIfNotExists()) {
                return;
            }

            throw labelAlreadyExists(typeName, label);
        }

        List<String> labels = new ArrayList<>(enumType.labels());

        labels.add(labelPosition(alterType, enumType), label);

        enums.set(index, new EnumType(enumType.name(), labels));
    }

    /** The position an added label takes among the type's existing labels. */
    private int labelPosition(AlterType alterType, EnumType enumType) {
        AlterType.Position position = alterType.getPosition();

        if (position == null) {
            return enumType.labels().size();
        }

        String neighbor = alterType.getNeighborValue().getNotExcapedValue();

        int index = enumType.labels().indexOf(neighbor);

        if (index < 0) {
            throw labelNotFound(enumType.name(), neighbor);
        }

        return position == AlterType.Position.BEFORE
            ? index
            : index + 1;
    }

    /**
     * Appends one domain over the type it is declared over, resolved against
     * the enum types and the domains the statements before it left, so a
     * domain over a domain carries the same type its base does. A domain over
     * a type sqlcj does not map is modeled with no type, so a column of it is
     * recorded with its declared type.
     *
     * <p>A modeled domain of the same name is replaced in its position,
     * because the {@code DROP DOMAIN} that freed the name may be one sqlcj
     * never sees. A domain of a schema sqlcj does not model is ignored.
     */
    private void applyCreateDomain(
        CreateDomain createDomain,
        List<EnumType> enums,
        List<DomainType> domains
    ) {
        String typeName = SchemaNamespace.modeledTypeName(createDomain.getName());

        if (typeName == null) {
            return;
        }

        ColumnTypeMapping.MappedType base = columnTypeMapping.map(
            createDomain.getDataType(),
            enums,
            domains
        );

        addDomain(
            base.mapped()
                ? new DomainType(
                    typeName,
                    base.type(),
                    base.enumType(),
                    base.array(),
                    base.blankPadded()
                )
                : new DomainType(typeName, null, null, false, false),
            domains
        );
    }

    /**
     * Models one {@code CREATE DOMAIN} the parser cannot read, which is the
     * form {@code pg_dump} writes for a domain with a constraint or a
     * {@code COLLATE}, from the statement's own words: the words before its
     * first top-level constraint word state the name and the base type alone,
     * so they are parsed again as a {@code CREATE DOMAIN} of their own and
     * applied like a readable one.
     *
     * <p>Words that still do not parse that way state a name but a base type
     * sqlcj cannot resolve, so the domain is modeled over no type, and words
     * that state no name at all fail the statement with the parser's own
     * reason. A domain of a schema sqlcj does not model is ignored, like a
     * readable one.
     */
    private void applyUnreadableCreateDomain(
        SchemaSourceSplitter.SourceStatement statement,
        JSQLParserException failure,
        List<EnumType> enums,
        List<DomainType> domains
    ) {
        List<String> tokens = statement.tokens();

        if (namesAnotherSchema("CREATE DOMAIN", tokens)) {
            return;
        }

        int nameEnd = afterTypeName(tokens, 2);

        if (nameEnd < 0) {
            if (failure != null) {
                throw unreadableStatement(statement, failure);
            }

            throw unsupportedOpening("CREATE DOMAIN", statement.line());
        }

        String typeName = SchemaNamespace.modeledTypeName(declaredName(tokens, 2, nameEnd));

        if (typeName == null) {
            return;
        }

        CreateDomain createDomain = declaredCreateDomain(tokens, nameEnd);

        if (createDomain != null) {
            applyCreateDomain(createDomain, enums, domains);

            return;
        }

        addDomain(new DomainType(typeName, null, null, false, false), domains);
    }

    /**
     * The {@code CREATE DOMAIN} the words of an unreadable one state up to its
     * first constraint word, or {@code null} when those words do not parse as
     * exactly one {@code CREATE DOMAIN}. A constraint word is {@code COLLATE},
     * {@code DEFAULT}, {@code CONSTRAINT}, {@code NOT}, {@code NULL}, or
     * {@code CHECK} written outside parentheses after the domain's name, which
     * is where PostgreSQL ends the base type.
     */
    private CreateDomain declaredCreateDomain(List<String> tokens, int typeFrom) {
        int depth = 0;
        int end = tokens.size();

        for (int i = typeFrom; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if ("(".equals(token)) {
                depth++;
            } else if (")".equals(token)) {
                depth--;
            } else if (depth == 0 && isDomainConstraintWord(token)) {
                end = i;

                break;
            }
        }

        try {
            Statements parsed = CCJSqlParserUtil.parseStatements(
                String.join(" ", tokens.subList(0, end))
            );

            return parsed.size() == 1 && parsed.get(0) instanceof CreateDomain createDomain
                ? createDomain
                : null;
        } catch (JSQLParserException e) {
            return null;
        }
    }

    private boolean isDomainConstraintWord(String token) {
        return switch (upperCase(token)) {
            case "COLLATE", "DEFAULT", "CONSTRAINT", "NOT", "NULL", "CHECK" -> true;
            default -> false;
        };
    }

    /**
     * Renames one modeled domain in its position. Every other
     * {@code ALTER DOMAIN} action leaves the domains unchanged: none of them
     * can change the type a column of the domain has, and a domain's own
     * {@code NOT NULL} never decides a column's nullability. A domain sqlcj
     * does not model, one of a schema it does not model included, is ignored.
     */
    private void applyAlterDomain(AlterDomain alterDomain, List<DomainType> domains) {
        if (alterDomain.getAction() != AlterDomain.Action.RENAME) {
            return;
        }

        String typeName = SchemaNamespace.modeledTypeName(alterDomain.getName());

        if (typeName == null) {
            return;
        }

        int index = indexOfDomain(domains, typeName);

        if (index >= 0) {
            renameDomain(MultiPartName.unquote(alterDomain.getNewName()), index, domains);
        }
    }

    /**
     * Removes every modeled domain one {@code DROP DOMAIN} names, which is
     * decided from the statement's own words, because the parser reads no
     * {@code DROP DOMAIN} at all. A name sqlcj does not model, one of another
     * schema included, is skipped, as a {@code DROP TABLE} skips one.
     *
     * <p>The columns of a dropped domain are not changed: PostgreSQL removes
     * them only with {@code CASCADE}, and a column already resolved keeps the
     * type its own statement gave it in any case.
     *
     * <p>Words that state no such statement are rejected naming the opening,
     * because sqlcj cannot tell what they would drop.
     */
    private void applyDropDomain(
        SchemaSourceSplitter.SourceStatement statement,
        List<DomainType> domains
    ) {
        List<String> names = droppedNames(statement.tokens(), 2);

        if (names == null) {
            throw unsupportedOpening("DROP DOMAIN", statement.line());
        }

        for (String declaredName : names) {
            String typeName = SchemaNamespace.modeledTypeName(declaredName);

            if (typeName != null) {
                removeDomain(domains, typeName);
            }
        }
    }

    /**
     * The declared names of one {@code DROP}, whose words must state
     * {@code [IF EXISTS] <name> [, <name> ...] [CASCADE | RESTRICT]} from
     * {@code index} on, or {@code null} when they state anything else.
     * {@code index} stands after the dropped object's words, which are two for
     * a {@code DROP DOMAIN} or a {@code DROP VIEW} and three for a
     * {@code DROP MATERIALIZED VIEW}.
     */
    private List<String> droppedNames(List<String> tokens, int index) {
        if (statesWords(tokens, index, "IF", "EXISTS")) {
            index += 2;
        }

        List<String> names = new ArrayList<>();

        while (true) {
            int end = afterTypeName(tokens, index);

            if (end < 0) {
                return null;
            }

            names.add(declaredName(tokens, index, end));

            index = end;

            if (!statesWords(tokens, index, ",")) {
                break;
            }

            index++;
        }

        if (statesWords(tokens, index, "CASCADE") || statesWords(tokens, index, "RESTRICT")) {
            index++;
        }

        return index == tokens.size()
            ? names
            : null;
    }

    /**
     * The index after the possibly qualified type name that begins at
     * {@code index}, or {@code -1} when the words state no such name. Each
     * part is one identifier, delimited or not, and the parts are separated by
     * dots, as the lexer reports them.
     */
    private int afterTypeName(List<String> tokens, int index) {
        while (true) {
            if (index >= tokens.size() || !IDENTIFIER.matcher(tokens.get(index)).matches()) {
                return -1;
            }

            index++;

            if (!statesWords(tokens, index, ".")) {
                return index;
            }

            index++;
        }
    }

    /**
     * The declared name the words from {@code from} to {@code to} state,
     * joined without spaces, so a qualified name reads as one text whatever
     * whitespace was written around its dots.
     */
    private String declaredName(List<String> tokens, int from, int to) {
        return String.join("", tokens.subList(from, to));
    }

    /**
     * Appends one domain, and replaces a modeled domain of the same name in
     * its position.
     */
    private void addDomain(DomainType domain, List<DomainType> domains) {
        int index = indexOfDomain(domains, domain.name());

        if (index < 0) {
            domains.add(domain);

            return;
        }

        domains.set(index, domain);
    }

    /**
     * Renames one domain in its position, keeping the type it is declared
     * over. A modeled domain of the new name is removed, because PostgreSQL
     * holds one type of a name at a time.
     */
    private void renameDomain(String newName, int index, List<DomainType> domains) {
        int replaced = indexOfDomain(domains, newName);
        int renamed = index;

        if (replaced >= 0 && replaced != index) {
            domains.remove(replaced);

            if (replaced < index) {
                renamed--;
            }
        }

        DomainType domain = domains.get(renamed);

        domains.set(
            renamed,
            new DomainType(
                newName,
                domain.type(),
                domain.enumType(),
                domain.array(),
                domain.blankPadded()
            )
        );
    }

    /** Removes the modeled domain of {@code typeName}, if the schema has one. */
    private void removeDomain(List<DomainType> domains, String typeName) {
        int index = indexOfDomain(domains, typeName);

        if (index >= 0) {
            domains.remove(index);
        }
    }

    /** Names {@code newName} as the enum type of a domain over {@code oldName}. */
    private DomainType renameDomainEnumType(DomainType domain, String oldName, String newName) {
        if (domain.enumType() == null || !domain.enumType().equalsIgnoreCase(oldName)) {
            return domain;
        }

        return new DomainType(
            domain.name(),
            domain.type(),
            newName,
            domain.array(),
            domain.blankPadded()
        );
    }

    /** Domain names are matched case-insensitively, as a column names them. */
    private int indexOfDomain(List<DomainType> domains, String typeName) {
        for (int i = 0; i < domains.size(); i++) {
            if (domains.get(i).name().equalsIgnoreCase(typeName)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Appends one table. A repeated table name fails, as PostgreSQL rejects it,
     * unless the statement declares {@code IF NOT EXISTS}. A clause that states
     * columns sqlcj cannot determine is rejected naming the clause.
     */
    private void applyCreateTable(
        CreateTable createTable,
        int line,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables
    ) {
        String tableName = createTable.getTable().getUnquotedName();

        if (indexOfTable(tables, tableName) >= 0) {
            if (createTable.isIfNotExists()) {
                return;
            }

            throw tableAlreadyExists(tableName);
        }

        rejectUnsupportedCreateTableClause(createTable, line);

        tables.add(parseTable(createTable, line, enums, domains, tables));
    }

    /**
     * Rejects the {@code CREATE TABLE} clauses whose columns stand nowhere in
     * the schema source: a query, a composite type, and an inherited table,
     * whose parent's columns PostgreSQL copies and keeps following.
     */
    private void rejectUnsupportedCreateTableClause(CreateTable createTable, int line) {
        if (createTable.getSelect() != null) {
            throw unsupportedCreateTableClause("AS SELECT", line);
        }

        if (createTable.getOfType() != null) {
            throw unsupportedCreateTableClause("OF " + createTable.getOfType(), line);
        }

        if (inheritsAnotherTable(createTable)) {
            throw unsupportedCreateTableClause("INHERITS", line);
        }
    }

    /**
     * Reports whether a {@code CREATE TABLE} states {@code INHERITS}, which the
     * parser reports only among the table options, with the case it was written
     * in.
     */
    private boolean inheritsAnotherTable(CreateTable createTable) {
        List<String> options = createTable.getTableOptionsStrings();

        return options != null && options.stream().anyMatch("INHERITS"::equalsIgnoreCase);
    }

    /**
     * Removes every named table, and every partition of one, keeping the order
     * of the remaining ones. A name a preceding name of the same statement
     * already removed as a partition of it is left alone, because it is dropped
     * either way.
     */
    private void applyDropTable(Drop drop, List<Table> tables) {
        for (String tableName : droppedTableNames(drop, tables)) {
            int index = indexOfTable(tables, tableName);

            if (index >= 0) {
                dropTable(tables, index);
            }
        }
    }

    /**
     * The modeled names of one {@code DROP TABLE}, all resolved against the
     * tables as they stand before it drops anything, as PostgreSQL resolves
     * every name of one statement before deleting any of them. A name that is
     * not modeled fails, unless the statement declares {@code IF EXISTS}.
     *
     * <p>A name qualified with a schema sqlcj does not model names a table it
     * leaves unmodeled and is skipped without failing, because the statement
     * may name a modeled table beside it.
     */
    private List<String> droppedTableNames(Drop drop, List<Table> tables) {
        List<String> names = new ArrayList<>();

        for (net.sf.jsqlparser.schema.Table dropped : drop.getNames()) {
            if (!SchemaNamespace.isModeled(dropped)) {
                continue;
            }

            String tableName = dropped.getUnquotedName();

            if (indexOfTable(tables, tableName) < 0) {
                if (drop.isIfExists()) {
                    continue;
                }

                throw tableNotFound(tableName, tables);
            }

            names.add(tableName);
        }

        return names;
    }

    /**
     * Removes one table and, recursively, every partition of it, as PostgreSQL
     * drops the partitions of a partitioned table along with it.
     */
    private void dropTable(List<Table> tables, int index) {
        String tableName = tables.get(index).name();

        tables.remove(index);

        int partition = indexOfPartition(tables, tableName);

        while (partition >= 0) {
            dropTable(tables, partition);

            partition = indexOfPartition(tables, tableName);
        }
    }

    /** The first partition of {@code parentName}, or {@code -1}. */
    private int indexOfPartition(List<Table> tables, String parentName) {
        for (int i = 0; i < tables.size(); i++) {
            String parent = tables.get(i).partitionOf();

            if (parent != null && parent.equalsIgnoreCase(parentName)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Applies the actions of one {@code ALTER TABLE} in order, in the position
     * of the altered table. {@code ALTER TABLE IF EXISTS} covers only the table,
     * so a missing column of an existing table still fails.
     */
    private void applyAlterTable(
        Alter alter,
        int line,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables
    ) {
        String tableName = alter.getTable().getUnquotedName();

        int index = indexOfTable(tables, tableName);

        if (index < 0) {
            if (alter.isUseTableIfExists()) {
                return;
            }

            throw tableNotFound(tableName, tables);
        }

        String schemaName = soleSetSchemaAction(alter);

        if (schemaName != null) {
            moveTableToSchema(line, schemaName, tables, index);

            return;
        }

        for (AlterExpression expression : alter.getAlterExpressions()) {
            applyToTableAndPartitions(line, expression, enums, domains, tables, index);
        }
    }

    /**
     * The schema a {@code SET SCHEMA} written as the statement's sole action
     * names, or {@code null} for every other {@code ALTER TABLE}, including a
     * {@code SET SCHEMA} written beside another action, which PostgreSQL does
     * not accept and sqlcj rejects.
     *
     * <p>The parser has no form of its own for the action and reports it as the
     * text that runs to the end of the statement, so that text is lexed with
     * the parser's own lexer and must state exactly {@code SET SCHEMA <schema>}.
     */
    private String soleSetSchemaAction(Alter alter) {
        List<AlterExpression> expressions = alter.getAlterExpressions();

        if (expressions.size() != 1) {
            return null;
        }

        AlterExpression expression = expressions.get(0);

        if (expression.getOperation() != AlterOperation.UNSPECIFIC || expression.getOptionalSpecifier() == null) {
            return null;
        }

        List<String> tokens = SchemaSourceSplitter.tokens(expression.getOptionalSpecifier());

        return tokens.size() == 3 && statesWords(tokens, 0, "SET", "SCHEMA")
            ? tokens.get(2)
            : null;
    }

    /**
     * Moves one table out of the schema model, because sqlcj models the tables
     * of one namespace alone: the table is removed, keeping the order of the
     * remaining ones, and a move into the namespace it models leaves the table
     * where it already is.
     *
     * <p>A table with modeled partitions is rejected naming the action, because
     * its partitions would stay in the modeled namespace following a parent
     * sqlcj no longer models.
     */
    private void moveTableToSchema(int line, String schemaName, List<Table> tables, int index) {
        if (SchemaNamespace.isModeled(schemaName)) {
            return;
        }

        if (indexOfPartition(tables, tables.get(index).name()) >= 0) {
            throw unsupportedAlterTableAction(line, "SET SCHEMA " + schemaName);
        }

        tables.remove(index);
    }

    /**
     * Applies one {@code ALTER TABLE} action to the table at {@code index} and,
     * when the action changes a modeled column, to every partition of that
     * table, recursively, because PostgreSQL applies a partitioned table's
     * column changes to its partitions. A rename of the table itself is not
     * propagated: a partition keeps its own name.
     */
    private void applyToTableAndPartitions(
        int line,
        AlterExpression expression,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables,
        int index
    ) {
        String tableName = tables.get(index).name();

        tables.set(index, applyAlterExpression(line, expression, enums, domains, tables, index));

        if (!propagatesToPartitions(expression)) {
            return;
        }

        for (int i = 0; i < tables.size(); i++) {
            String parent = tables.get(i).partitionOf();

            if (parent != null && parent.equalsIgnoreCase(tableName)) {
                applyToTableAndPartitions(line, expression, enums, domains, tables, i);
            }
        }
    }

    /**
     * Reports whether one {@code ALTER TABLE} action changes a modeled column
     * and therefore reaches the partitions of its table: {@code ADD COLUMN},
     * {@code DROP COLUMN}, {@code RENAME COLUMN}, a new column type,
     * {@code SET NOT NULL}, and {@code DROP NOT NULL}.
     */
    private boolean propagatesToPartitions(AlterExpression expression) {
        AlterOperation operation = expression.getOperation();

        if (operation == AlterOperation.ADD) {
            return isNotEmpty(expression.getColDataTypeList());
        }

        if (operation == AlterOperation.DROP) {
            return expression.getColumnName() != null;
        }

        if (operation == AlterOperation.RENAME) {
            return expression.getColumnOldName() != null;
        }

        if (operation != AlterOperation.ALTER) {
            return false;
        }

        if (isNotEmpty(expression.getColumnSetDefaultList()) || isNotEmpty(expression.getColumnDropDefaultList())) {
            return false;
        }

        if (isNotEmpty(expression.getColDataTypeList())) {
            return statesNewTypes(expression.getColDataTypeList());
        }

        return isNotEmpty(expression.getColumnSetNotNullList())
            || isNotEmpty(expression.getColumnDropNotNullList());
    }

    /**
     * Applies one {@code ALTER TABLE} action and returns the altered table.
     *
     * <p>An action that cannot change a modeled column's existence, name, type,
     * or nullability is accepted and leaves the table unchanged. An action that
     * would change a column in a way sqlcj does not model, and an action sqlcj
     * does not recognize, is rejected naming the action's own SQL.
     */
    private Table applyAlterExpression(
        int line,
        AlterExpression expression,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables,
        int index
    ) {
        Table table = tables.get(index);
        AlterOperation operation = expression.getOperation();

        if (operation == AlterOperation.ADD && isNotEmpty(expression.getColDataTypeList())) {
            return addColumns(expression, enums, domains, table);
        }

        if (operation == AlterOperation.DROP && expression.getColumnName() != null) {
            return dropColumn(expression, table);
        }

        if (operation == AlterOperation.RENAME && expression.getColumnOldName() != null) {
            return renameColumn(expression, table);
        }

        if (operation == AlterOperation.RENAME_TABLE) {
            return renameTable(expression, tables, index);
        }

        if (operation == AlterOperation.ALTER) {
            return alterColumns(line, expression, enums, domains, table);
        }

        if (operation == AlterOperation.ATTACH_PARTITION || operation == AlterOperation.DETACH_PARTITION) {
            return changePartitionLink(expression, tables, table);
        }

        if (isIgnoredConstraintAction(expression) || isIgnoredTableAction(operation)) {
            return table;
        }

        if (operation == AlterOperation.UNSPECIFIC) {
            return ignoreUnspecificActions(line, expression, table);
        }

        throw unsupportedAlterTableAction(line, expression);
    }

    /**
     * Links or unlinks one partition of the altered table, leaving the altered
     * table itself unchanged. {@code ATTACH PARTITION} links the stated table to
     * the altered one, so it follows its column changes from then on, and
     * {@code DETACH PARTITION} ends that link. A partition sqlcj does not model
     * is ignored, like any other statement about an unmodeled table.
     */
    private Table changePartitionLink(
        AlterExpression expression,
        List<Table> tables,
        Table table
    ) {
        if (!(expression instanceof AlterExpressionPartition partition)) {
            return table;
        }

        int index = indexOfReferencedTable(tables, partition.getPartitionTable());

        if (index < 0) {
            return table;
        }

        Table attached = tables.get(index);

        tables.set(
            index,
            new Table(
                attached.name(),
                attached.columns(),
                attached.constraints(),
                expression.getOperation() == AlterOperation.ATTACH_PARTITION
                    ? table.name()
                    : null
            )
        );

        return table;
    }

    /**
     * Reports whether one {@code ALTER TABLE} action is a table-level action the
     * parser has a form of its own for that cannot change a column: the
     * row-level-security switches.
     */
    private boolean isIgnoredTableAction(AlterOperation operation) {
        return switch (operation) {
            case ENABLE_ROW_LEVEL_SECURITY,
                DISABLE_ROW_LEVEL_SECURITY,
                FORCE_ROW_LEVEL_SECURITY,
                NO_FORCE_ROW_LEVEL_SECURITY -> true;
            default -> false;
        };
    }

    /**
     * Accepts the actions the parser has no form of its own for, which it
     * reports as one piece of text running to the end of the statement, so
     * several written actions may stand in it. The text is lexed with the
     * parser's own lexer and split at the commas outside parentheses, and each
     * of its actions must begin as a table-level action that cannot change a
     * column. The first action that does not is rejected, so a modeled action
     * written after such an action is rejected rather than silently skipped.
     */
    private Table ignoreUnspecificActions(int line, AlterExpression expression, Table table) {
        String specifier = expression.getOptionalSpecifier();

        if (specifier == null) {
            throw unsupportedAlterTableAction(line, expression);
        }

        for (List<String> action : topLevelActions(SchemaSourceSplitter.tokens(specifier))) {
            if (!ignoresTableLevelAction(action)) {
                throw unsupportedAlterTableAction(line, String.join(" ", action));
            }
        }

        return table;
    }

    /**
     * Reports whether one action of the unspecific text begins as a
     * PostgreSQL 16 table-level action that cannot change a column: ownership,
     * trigger and rule switches, constraint validation, replica identity,
     * clustering, storage parameters, the tablespace, logging, the access
     * method, inheritance, and the typed-table form. {@code SET SCHEMA} is not
     * among them: it moves the table out of the namespace sqlcj models and is
     * applied as the statement's sole action, which is the only form PostgreSQL
     * accepts, so written beside another action it is rejected here.
     */
    private boolean ignoresTableLevelAction(List<String> action) {
        return statesWords(action, 0, "OWNER", "TO")
            || switchesTriggerOrRule(action)
            || statesWords(action, 0, "VALIDATE", "CONSTRAINT")
            || statesWords(action, 0, "REPLICA", "IDENTITY")
            || statesWords(action, 0, "CLUSTER", "ON")
            || statesWords(action, 0, "SET", "WITHOUT", "CLUSTER")
            || statesWords(action, 0, "SET", "WITHOUT", "OIDS")
            || statesWords(action, 0, "SET", "(")
            || statesWords(action, 0, "RESET", "(")
            || statesWords(action, 0, "SET", "TABLESPACE")
            || statesWords(action, 0, "SET", "LOGGED")
            || statesWords(action, 0, "SET", "UNLOGGED")
            || statesWords(action, 0, "SET", "ACCESS", "METHOD")
            || statesWords(action, 0, "INHERIT")
            || statesWords(action, 0, "NO", "INHERIT")
            || statesWords(action, 0, "OF")
            || statesWords(action, 0, "NOT", "OF");
    }

    /**
     * Reports whether one action switches a trigger or a rule:
     * {@code ENABLE} or {@code DISABLE}, and {@code ENABLE REPLICA} or
     * {@code ENABLE ALWAYS}, of either object.
     */
    private boolean switchesTriggerOrRule(List<String> action) {
        for (String object : List.of("TRIGGER", "RULE")) {
            if (
                statesWords(action, 0, "ENABLE", object)
                    || statesWords(action, 0, "DISABLE", object)
                    || statesWords(action, 0, "ENABLE", "REPLICA", object)
                    || statesWords(action, 0, "ENABLE", "ALWAYS", object)
            ) {
                return true;
            }
        }

        return false;
    }

    /**
     * Reports whether one {@code ALTER TABLE} action states a constraint sqlcj
     * accepts without modeling it: adding a primary-key, unique, foreign-key, or
     * check constraint, dropping a named constraint, or renaming one. The table
     * itself is still resolved, so a missing table fails as it does for any
     * other {@code ALTER TABLE}.
     */
    private boolean isIgnoredConstraintAction(AlterExpression expression) {
        AlterOperation operation = expression.getOperation();

        if (operation == AlterOperation.ADD) {
            return !isNotEmpty(expression.getColDataTypeList())
                && isIgnoredConstraintKind(expression.getIndex());
        }

        if (operation == AlterOperation.DROP) {
            return expression.getColumnName() == null
                && expression.getConstraintName() != null;
        }

        return operation == AlterOperation.RENAME_CONSTRAINT;
    }

    private boolean isIgnoredConstraintKind(Index index) {
        if (index == null) {
            return false;
        }

        return switch (index.getKind()) {
            case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK -> true;
            default -> false;
        };
    }

    /**
     * Applies one {@code ALTER COLUMN} action: a new type, which keeps the
     * column's position and nullability, or a nullability change. A column
     * action that cannot change the column sqlcj models leaves the table
     * unchanged, without its column being resolved.
     */
    private Table alterColumns(
        int line,
        AlterExpression expression,
        List<EnumType> enums,
        List<DomainType> domains,
        Table table
    ) {
        if (isNotEmpty(expression.getColumnSetDefaultList()) || isNotEmpty(expression.getColumnDropDefaultList())) {
            return table;
        }

        if (isNotEmpty(expression.getColDataTypeList())) {
            if (statesNewTypes(expression.getColDataTypeList())) {
                return changeColumnTypes(expression, enums, domains, table);
            }

            if (ignoresColumnActions(expression.getColDataTypeList())) {
                return table;
            }

            throw unsupportedAlterTableAction(line, expression);
        }

        if (isNotEmpty(expression.getColumnSetNotNullList())) {
            return changeNullability(
                expression.getColumnSetNotNullList().stream()
                    .map(AlterExpression.ColumnSetNotNull::getColumnName)
                    .toList(),
                false,
                table
            );
        }

        if (isNotEmpty(expression.getColumnDropNotNullList())) {
            return changeNullability(
                expression.getColumnDropNotNullList().stream()
                    .map(AlterExpression.ColumnDropNotNull::getColumnName)
                    .toList(),
                true,
                table
            );
        }

        throw unsupportedAlterTableAction(line, expression);
    }

    /**
     * Reports whether every entry of an {@code ALTER COLUMN} action states a new
     * type. The parser reports other column actions, such as
     * {@code SET STATISTICS} or an identity change, in the same list without a
     * declared type.
     */
    private boolean statesNewTypes(List<AlterExpression.ColumnDataType> definitions) {
        return definitions.stream()
            .allMatch(
                definition -> definition.isWithType() && definition.getColDataType() != null
            );
    }

    /**
     * Reports whether every entry of an {@code ALTER COLUMN} action states a
     * column action that cannot change the column sqlcj models.
     */
    private boolean ignoresColumnActions(List<AlterExpression.ColumnDataType> definitions) {
        return definitions.stream().allMatch(this::ignoresColumnAction);
    }

    /**
     * Reports whether one entry of an {@code ALTER COLUMN} action states an
     * identity change, {@code SET STATISTICS}, {@code SET STORAGE},
     * {@code SET COMPRESSION}, or {@code DROP EXPRESSION}. None of them can
     * change a column's existence, name, type, or nullability, which comes from
     * {@code NOT NULL} alone; PostgreSQL requires a column to be
     * {@code NOT NULL} already before {@code ADD GENERATED ... AS IDENTITY}. The
     * parser reports each of them in the entry list of the action, without a
     * declared type.
     */
    private boolean ignoresColumnAction(AlterExpression.ColumnDataType definition) {
        if (definition.isWithType()) {
            return false;
        }

        if (isNotEmpty(definition.getIdentityAlterations())) {
            return true;
        }

        if (definition.getColDataType() == null) {
            return statesWords(definition.getColumnSpecs(), 0, "DROP", "EXPRESSION");
        }

        if (!"SET".equalsIgnoreCase(definition.getColDataType().getDataType())) {
            return false;
        }

        return statesWords(definition.getColumnSpecs(), 0, "STATISTICS")
            || statesWords(definition.getColumnSpecs(), 0, "STORAGE")
            || statesWords(definition.getColumnSpecs(), 0, "COMPRESSION");
    }

    /**
     * Appends each added column, typed exactly as a {@code CREATE TABLE} column
     * of the same declaration, and records its column-level constraints.
     */
    private Table addColumns(
        AlterExpression expression,
        List<EnumType> enums,
        List<DomainType> domains,
        Table table
    ) {
        List<Column> columns = new ArrayList<>(table.columns());
        List<Constraint> constraints = new ArrayList<>(table.constraints());

        for (AlterExpression.ColumnDataType definition : expression.getColDataTypeList()) {
            Column column = parseColumn(definition, enums, domains);

            if (indexOfColumn(columns, column.name()) >= 0) {
                if (expression.isUseIfNotExists()) {
                    continue;
                }

                throw columnAlreadyExists(table.name(), column.name());
            }

            columns.add(column);
            constraints.addAll(parseColumnConstraints(definition));
        }

        return new Table(table.name(), columns, constraints, table.partitionOf());
    }

    /** Removes one column and every constraint that lists it. */
    private Table dropColumn(AlterExpression expression, Table table) {
        String columnName = MultiPartName.unquote(expression.getColumnName());

        int index = indexOfColumn(table.columns(), columnName);

        if (index < 0) {
            if (expression.isUsingIfExists()) {
                return table;
            }

            throw columnNotFound(table.name(), columnName);
        }

        List<Column> columns = new ArrayList<>(table.columns());

        columns.remove(index);

        List<Constraint> constraints = table.constraints().stream()
            .filter(constraint -> !listsColumn(constraint, columnName))
            .toList();

        return new Table(table.name(), columns, constraints, table.partitionOf());
    }

    /**
     * Renames one column in its position and in the constraints that list it.
     */
    private Table renameColumn(AlterExpression expression, Table table) {
        String oldName = MultiPartName.unquote(expression.getColumnOldName());
        String newName = MultiPartName.unquote(expression.getColumnName());

        int index = indexOfColumn(table.columns(), oldName);

        if (index < 0) {
            throw columnNotFound(table.name(), oldName);
        }

        int existing = indexOfColumn(table.columns(), newName);

        if (existing >= 0 && existing != index) {
            throw columnAlreadyExists(table.name(), newName);
        }

        List<Column> columns = new ArrayList<>(table.columns());
        Column column = columns.get(index);

        columns.set(
            index,
            new Column(
                newName,
                column.type(),
                column.nullable(),
                column.unsupportedType(),
                column.enumType(),
                column.array(),
                column.blankPadded()
            )
        );

        List<Constraint> constraints = table.constraints().stream()
            .map(constraint -> renameConstraintColumn(constraint, oldName, newName))
            .toList();

        return new Table(table.name(), columns, constraints, table.partitionOf());
    }

    /**
     * Renames one table in its position, keeping its columns and constraints,
     * and names the new name in every partition of it, so a partition keeps
     * following its parent.
     */
    private Table renameTable(AlterExpression expression, List<Table> tables, int index) {
        String newName = MultiPartName.unquote(expression.getNewTableName());

        int existing = indexOfTable(tables, newName);

        if (existing >= 0 && existing != index) {
            throw tableAlreadyExists(newName);
        }

        Table table = tables.get(index);

        tables.replaceAll(partition -> renamePartitionParent(partition, table.name(), newName));

        return new Table(newName, table.columns(), table.constraints(), table.partitionOf());
    }

    /** Names {@code newName} as the parent of a partition of {@code oldName}. */
    private Table renamePartitionParent(Table table, String oldName, String newName) {
        if (table.partitionOf() == null || !table.partitionOf().equalsIgnoreCase(oldName)) {
            return table;
        }

        return new Table(table.name(), table.columns(), table.constraints(), newName);
    }

    /**
     * Retypes each named column in its position, mapping or recording the new
     * type as a {@code CREATE TABLE} column of the same type would be, and
     * keeping the column's nullability, which a type change does not state.
     */
    private Table changeColumnTypes(
        AlterExpression expression,
        List<EnumType> enums,
        List<DomainType> domains,
        Table table
    ) {
        List<Column> columns = new ArrayList<>(table.columns());

        for (AlterExpression.ColumnDataType definition : expression.getColDataTypeList()) {
            String columnName = MultiPartName.unquote(definition.getColumnName());

            int index = indexOfColumn(columns, columnName);

            if (index < 0) {
                throw columnNotFound(table.name(), columnName);
            }

            Column column = columns.get(index);
            Column retyped = parseColumn(definition, enums, domains);

            columns.set(
                index,
                new Column(
                    column.name(),
                    retyped.type(),
                    column.nullable(),
                    retyped.unsupportedType(),
                    retyped.enumType(),
                    retyped.array(),
                    retyped.blankPadded()
                )
            );
        }

        return new Table(table.name(), columns, table.constraints(), table.partitionOf());
    }

    /** Sets the nullability of each named column in its position. */
    private Table changeNullability(
        List<String> declaredNames,
        boolean nullable,
        Table table
    ) {
        List<Column> columns = new ArrayList<>(table.columns());

        for (String declaredName : declaredNames) {
            String columnName = MultiPartName.unquote(declaredName);

            int index = indexOfColumn(columns, columnName);

            if (index < 0) {
                throw columnNotFound(table.name(), columnName);
            }

            Column column = columns.get(index);

            columns.set(
                index,
                new Column(
                    column.name(),
                    column.type(),
                    nullable,
                    column.unsupportedType(),
                    column.enumType(),
                    column.array(),
                    column.blankPadded()
                )
            );
        }

        return new Table(table.name(), columns, table.constraints(), table.partitionOf());
    }

    private boolean listsColumn(Constraint constraint, String columnName) {
        return constraint.columns().stream()
            .anyMatch(column -> column.equalsIgnoreCase(columnName));
    }

    private Constraint renameConstraintColumn(
        Constraint constraint,
        String oldName,
        String newName
    ) {
        return new Constraint(
            constraint.type(),
            constraint.columns().stream()
                .map(column -> column.equalsIgnoreCase(oldName) ? newName : column)
                .toList()
        );
    }

    /**
     * Table and column names are matched case-insensitively, as query analysis
     * looks them up.
     */
    private int indexOfTable(List<Table> tables, String tableName) {
        for (int i = 0; i < tables.size(); i++) {
            if (tables.get(i).name().equalsIgnoreCase(tableName)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * The position of the table one statement names beside the table it
     * creates or alters, the source of a {@code LIKE} or {@code PARTITION OF}
     * and the partition of {@code ATTACH} or {@code DETACH PARTITION}, or
     * {@code -1} when sqlcj does not model it.
     *
     * <p>sqlcj models one namespace, so an unqualified and a
     * {@code public}-qualified name are the same table, and a name qualified
     * with any other schema names a table sqlcj leaves unmodeled rather than
     * the modeled table of the same unqualified name; see
     * {@link SchemaNamespace}.
     */
    private int indexOfReferencedTable(
        List<Table> tables,
        net.sf.jsqlparser.schema.Table referenced
    ) {
        if (!SchemaNamespace.isModeled(referenced)) {
            return -1;
        }

        return indexOfTable(tables, referenced.getUnquotedName());
    }

    /** Enum type names are matched case-insensitively, as a column names them. */
    private int indexOfEnum(List<EnumType> enums, String typeName) {
        for (int i = 0; i < enums.size(); i++) {
            if (enums.get(i).name().equalsIgnoreCase(typeName)) {
                return i;
            }
        }

        return -1;
    }

    private int indexOfColumn(List<Column> columns, String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(columnName)) {
                return i;
            }
        }

        return -1;
    }

    private boolean isNotEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    /**
     * The rejection of a statement that names a table sqlcj does not model,
     * which suggests the modeled table closest to the written name, compared
     * with the tables modeled so far in declaration order.
     */
    private IllegalArgumentException tableNotFound(String tableName, List<Table> tables) {
        String suggestion = Suggestions.closest(tableName, tableNames(tables))
            .map(closest -> ". Did you mean '" + closest + "'?")
            .orElse("");

        return new IllegalArgumentException(
            "Table not found in schema: " + tableName + suggestion
        );
    }

    /** The modeled tables, in declaration order. */
    private List<String> tableNames(List<Table> tables) {
        return tables.stream()
            .map(Table::name)
            .toList();
    }

    private IllegalArgumentException tableAlreadyExists(String tableName) {
        return new IllegalArgumentException("Table already exists in schema: " + tableName);
    }

    private IllegalArgumentException columnNotFound(String tableName, String columnName) {
        return new IllegalArgumentException(
            "Column not found in table %s: %s".formatted(tableName, columnName)
        );
    }

    private IllegalArgumentException columnAlreadyExists(String tableName, String columnName) {
        return new IllegalArgumentException(
            "Column already exists in table %s: %s".formatted(tableName, columnName)
        );
    }

    private IllegalArgumentException typeNotFound(String typeName) {
        return new IllegalArgumentException("Type not found in schema: " + typeName);
    }

    private IllegalArgumentException labelNotFound(String typeName, String label) {
        return new IllegalArgumentException(
            "Label not found in type %s: %s".formatted(typeName, label)
        );
    }

    private IllegalArgumentException labelAlreadyExists(String typeName, String label) {
        return new IllegalArgumentException(
            "Label already exists in type %s: %s".formatted(typeName, label)
        );
    }

    /**
     * The failure of one rejected {@code ALTER TABLE} action, quoting the action
     * as the parser writes it back, with its whitespace runs collapsed to single
     * spaces.
     */
    private SchemaParseException unsupportedAlterTableAction(
        int line,
        AlterExpression expression
    ) {
        return unsupportedAlterTableAction(
            line,
            expression.toString().replaceAll("\\s+", " ").trim()
        );
    }

    private SchemaParseException unsupportedAlterTableAction(int line, String action) {
        return new SchemaParseException(
            "Unsupported ALTER TABLE action: " + action,
            line,
            null
        );
    }

    /** The failure of one rejected {@code ALTER TYPE} action, naming the action. */
    private SchemaParseException unsupportedAlterTypeAction(String action, int line) {
        return new SchemaParseException(
            "Unsupported ALTER TYPE action: " + action,
            line,
            null
        );
    }

    private SchemaParseException unsupportedOpening(String opening, int line) {
        return new SchemaParseException(
            "Unsupported schema statement: " + opening,
            line,
            null
        );
    }

    /**
     * The failure of one rejected {@code CREATE TABLE} clause, naming the
     * clause.
     */
    private SchemaParseException unsupportedCreateTableClause(String clause, int line) {
        return new SchemaParseException(
            "Unsupported CREATE TABLE clause: " + clause,
            line,
            null
        );
    }

    /**
     * Parses one table, whose columns come from its own column definitions, in
     * written order, from the modeled source of each {@code LIKE} at its
     * position in the element list, and, for a partition, from its parent
     * before all of them, as PostgreSQL creates them. A repeated column name
     * fails, as PostgreSQL rejects it.
     *
     * <p>A {@code LIKE}'s {@code INCLUDING} and {@code EXCLUDING} options
     * cannot change a copied column's type or nullability and are ignored, and
     * neither {@code LIKE} nor {@code PARTITION OF} copies its source's
     * recorded constraints. Only the table's own table constraints are
     * recorded, in a partition as in any other table.
     *
     * <p>A {@code CREATE TABLE} with neither an element list, the empty element
     * list {@code ()}, nor {@code PARTITION OF} states columns sqlcj cannot
     * determine and is rejected as unsupported table DDL.
     */
    private Table parseTable(
        CreateTable createTable,
        int line,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables
    ) {
        String tableName = createTable.getTable().getUnquotedName();

        List<Column> columns = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();
        String partitionOf = null;

        if (createTable.getPartitionOf() != null) {
            Table parent = referencedTable(tables, createTable.getPartitionOf());

            partitionOf = parent.name();
            columns.addAll(parent.columns());
        }

        List<TableElement> elements = createTable.getTableElements();

        if (elements == null) {
            if (partitionOf == null && !statesEmptyElementList(createTable)) {
                throw unsupportedOpening("CREATE TABLE", line);
            }
        } else {
            for (TableElement element : elements) {
                if (element instanceof ColumnDefinition definition) {
                    addColumn(columns, tableName, parseColumn(definition, enums, domains));
                    constraints.addAll(parseColumnConstraints(definition));
                } else if (element instanceof LikeClause like) {
                    for (Column column : referencedTable(tables, like.getTable()).columns()) {
                        addColumn(columns, tableName, column);
                    }
                }
            }
        }

        constraints.addAll(parseTableConstraints(createTable));

        return new Table(
            tableName,
            columns,
            constraints,
            partitionOf
        );
    }

    /**
     * Reports whether a {@code CREATE TABLE} states the empty element list
     * {@code ()}, a table without columns, which the parser reports as the
     * first of the table options instead of as an element list.
     */
    private boolean statesEmptyElementList(CreateTable createTable) {
        List<String> options = createTable.getTableOptionsStrings();

        return options != null && !options.isEmpty() && "()".equals(options.get(0));
    }

    /**
     * The modeled table a {@code LIKE} or {@code PARTITION OF} names as the
     * source of its columns. A source sqlcj does not model fails as a missing
     * table, because the created table's columns would otherwise be unknown.
     *
     * <p>A source qualified with a schema sqlcj does not model is compared with
     * no candidate, because what fails is its schema and not its spelling.
     */
    private Table referencedTable(
        List<Table> tables,
        net.sf.jsqlparser.schema.Table referenced
    ) {
        int index = indexOfReferencedTable(tables, referenced);

        if (index < 0) {
            throw tableNotFound(
                SchemaNamespace.declaredName(referenced),
                SchemaNamespace.isModeled(referenced)
                    ? tables
                    : List.of()
            );
        }

        return tables.get(index);
    }

    private void addColumn(List<Column> columns, String tableName, Column column) {
        if (indexOfColumn(columns, column.name()) >= 0) {
            throw columnAlreadyExists(tableName, column.name());
        }

        columns.add(column);
    }

    /**
     * Parses one column, whose declared type the shared column type mapping
     * resolves or records with its declared type text instead of failing the
     * schema.
     */
    private Column parseColumn(
        ColumnDefinition definition,
        List<EnumType> enums,
        List<DomainType> domains
    ) {
        ColumnTypeMapping.MappedType mappedType = columnTypeMapping.map(
            definition.getColDataType(),
            enums,
            domains
        );

        return new Column(
            columnName(definition),
            mappedType.type(),
            !isSerial(mappedType.typeName()) && isNullable(definition),
            mappedType.unsupportedType(),
            mappedType.enumType(),
            mappedType.array(),
            mappedType.blankPadded()
        );
    }

    /**
     * Returns the canonical column name without SQL identifier delimiters.
     */
    private String columnName(ColumnDefinition definition) {
        return MultiPartName.unquote(definition.getColumnName());
    }

    /**
     * PostgreSQL defines the serial spellings as an integer type with a
     * sequence default and {@code NOT NULL}, so such a column is never
     * nullable.
     */
    private boolean isSerial(String typeName) {
        return switch (typeName) {
            case "SERIAL", "BIGSERIAL", "SMALLSERIAL", "SERIAL2", "SERIAL4", "SERIAL8" -> true;
            default -> false;
        };
    }

    private boolean isNullable(ColumnDefinition definition) {
        List<String> specs = definition.getColumnSpecs();

        if (specs == null) {
            return true;
        }

        for (int i = 0; i < specs.size() - 1; i++) {
            if ("NOT".equalsIgnoreCase(specs.get(i)) && "NULL".equalsIgnoreCase(specs.get(i + 1))) {
                return false;
            }
        }

        return true;
    }

    private List<Constraint> parseColumnConstraints(ColumnDefinition definition) {
        List<String> specs = definition.getColumnSpecs();

        if (specs == null) {
            return List.of();
        }

        List<Constraint> constraints = new ArrayList<>();

        for (int i = 0; i < specs.size(); i++) {
            String spec = specs.get(i);

            if ("PRIMARY".equalsIgnoreCase(spec) && i + 1 < specs.size() && "KEY".equalsIgnoreCase(specs.get(i + 1))) {

                constraints.add(
                    new Constraint(
                        ConstraintType.PRIMARY_KEY,
                        List.of(columnName(definition))
                    )
                );
            }

            if ("UNIQUE".equalsIgnoreCase(spec)) {
                constraints.add(
                    new Constraint(
                        ConstraintType.UNIQUE,
                        List.of(columnName(definition))
                    )
                );
            }
        }

        return constraints;
    }

    private List<Constraint> parseTableConstraints(CreateTable createTable) {
        List<Index> indexes = createTable.getIndexes();

        if (indexes == null) {
            return List.of();
        }

        List<Constraint> constraints = new ArrayList<>();

        for (Index index : indexes) {
            if (isIgnoredTableConstraint(index)) {
                continue;
            }

            constraints.add(
                new Constraint(
                    constraintType(index),
                    index.getColumnsNames().stream()
                        .map(MultiPartName::unquote)
                        .toList()
                )
            );
        }

        return constraints;
    }

    /**
     * Foreign key, check, and exclusion constraints are accepted but not
     * modeled, because they do not affect the generated Java types.
     */
    private boolean isIgnoredTableConstraint(Index index) {
        return index instanceof ForeignKeyIndex
            || index instanceof CheckConstraint
            || index instanceof ExcludeConstraint;
    }

    private ConstraintType constraintType(Index index) {
        String indexType = index.getType();

        String declaredType = indexType == null
            ? ""
            : indexType.toUpperCase(Locale.ROOT);

        return switch (declaredType) {
            case "PRIMARY KEY" -> ConstraintType.PRIMARY_KEY;
            case "UNIQUE" -> ConstraintType.UNIQUE;
            default -> throw new UnsupportedOperationException(
                "Unsupported table constraint: " + indexType
            );
        };
    }
}
