package org.sqlcj.schema.parser;

import net.sf.jsqlparser.statement.alter.AlterExpression;
import org.sqlcj.diagnostic.Suggestions;
import org.sqlcj.schema.Column;
import org.sqlcj.schema.EnumType;
import org.sqlcj.schema.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The helpers the statement classes of the package share: the word matching a
 * statement is classified by, the names one {@code DROP} and one declaration
 * state, the column with a stated nullability, the lookups of a table,
 * column, or enum type, and the diagnostics a rejected statement carries.
 */
final class SchemaParserSupport {

    /**
     * Reports whether the tokens from {@code index} on are {@code words},
     * compared case-insensitively. A list the parser did not state at all states
     * no words.
     */
    boolean statesWords(List<String> tokens, int index, String... words) {
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

    String upperCase(String word) {
        return word.toUpperCase(Locale.ROOT);
    }

    /**
     * The declared names of one {@code DROP}, whose words must state
     * {@code [IF EXISTS] <name> [, <name> ...] [CASCADE | RESTRICT]} from
     * {@code index} on, or {@code null} when they state anything else.
     * {@code index} stands after the dropped object's words, which are two for
     * a {@code DROP DOMAIN} or a {@code DROP VIEW} and three for a
     * {@code DROP MATERIALIZED VIEW}.
     */
    List<String> droppedNames(List<String> tokens, int index) {
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
    int afterTypeName(List<String> tokens, int index) {
        while (true) {
            if (index >= tokens.size() || !DefaultSchemaParser.IDENTIFIER.matcher(tokens.get(index)).matches()) {
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
    String declaredName(List<String> tokens, int from, int to) {
        return String.join("", tokens.subList(from, to));
    }

    /** The same column with the stated nullability. */
    Column withNullability(Column column, boolean nullable) {
        return new Column(
            column.name(),
            column.type(),
            nullable,
            column.unsupportedType(),
            column.enumType(),
            column.array(),
            column.blankPadded(),
            column.sqlName()
        );
    }

    /**
     * Table and column names are matched case-insensitively, as query analysis
     * looks them up.
     */
    int indexOfTable(List<Table> tables, String tableName) {
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
     * <p>A query group models one namespace, so an unqualified name and a name
     * qualified with the modeled schema are the same table, and a name
     * qualified with any other schema names a table sqlcj leaves unmodeled
     * rather than the modeled table of the same unqualified name; see
     * {@link SchemaNamespace}.
     */
    int indexOfReferencedTable(
        List<Table> tables,
        net.sf.jsqlparser.schema.Table referenced,
        SchemaNamespace namespace
    ) {
        if (!namespace.isModeled(referenced)) {
            return -1;
        }

        return indexOfTable(tables, referenced.getUnquotedName());
    }

    /** Enum type names are matched case-insensitively, as a column names them. */
    int indexOfEnum(List<EnumType> enums, String typeName) {
        for (int i = 0; i < enums.size(); i++) {
            if (enums.get(i).name().equalsIgnoreCase(typeName)) {
                return i;
            }
        }

        return -1;
    }

    int indexOfColumn(List<Column> columns, String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(columnName)) {
                return i;
            }
        }

        return -1;
    }

    boolean isNotEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    /**
     * The rejection of a statement that names a table sqlcj does not model,
     * which suggests the modeled table closest to the written name, compared
     * with the tables modeled so far in declaration order.
     */
    IllegalArgumentException tableNotFound(String tableName, List<Table> tables) {
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

    IllegalArgumentException tableAlreadyExists(String tableName) {
        return new IllegalArgumentException("Table already exists in schema: " + tableName);
    }

    IllegalArgumentException columnNotFound(String tableName, String columnName) {
        return new IllegalArgumentException(
            "Column not found in table %s: %s".formatted(tableName, columnName)
        );
    }

    IllegalArgumentException columnAlreadyExists(String tableName, String columnName) {
        return new IllegalArgumentException(
            "Column already exists in table %s: %s".formatted(tableName, columnName)
        );
    }

    IllegalArgumentException typeNotFound(String typeName) {
        return new IllegalArgumentException("Type not found in schema: " + typeName);
    }

    IllegalArgumentException labelNotFound(String typeName, String label) {
        return new IllegalArgumentException(
            "Label not found in type %s: %s".formatted(typeName, label)
        );
    }

    IllegalArgumentException labelAlreadyExists(String typeName, String label) {
        return new IllegalArgumentException(
            "Label already exists in type %s: %s".formatted(typeName, label)
        );
    }

    /**
     * The failure of one rejected {@code ALTER TABLE} action, quoting the action
     * as the parser writes it back, with its whitespace runs collapsed to single
     * spaces.
     */
    SchemaParseException unsupportedAlterTableAction(
        int line,
        AlterExpression expression
    ) {
        return unsupportedAlterTableAction(
            line,
            expression.toString().replaceAll("\\s+", " ").trim()
        );
    }

    SchemaParseException unsupportedAlterTableAction(int line, String action) {
        return new SchemaParseException(
            "Unsupported ALTER TABLE action: " + action,
            line,
            null
        );
    }

    /** The failure of one rejected {@code ALTER TYPE} action, naming the action. */
    SchemaParseException unsupportedAlterTypeAction(String action, int line) {
        return new SchemaParseException(
            "Unsupported ALTER TYPE action: " + action,
            line,
            null
        );
    }

    SchemaParseException unsupportedOpening(String opening, int line) {
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
    SchemaParseException unsupportedCreateTableClause(String clause, int line) {
        return new SchemaParseException(
            "Unsupported CREATE TABLE clause: " + clause,
            line,
            null
        );
    }
}
