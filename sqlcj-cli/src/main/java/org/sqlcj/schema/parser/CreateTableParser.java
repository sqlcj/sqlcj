package org.sqlcj.schema.parser;

import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.statement.LikeClause;
import net.sf.jsqlparser.statement.create.table.CheckConstraint;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ExcludeConstraint;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.create.table.TableElement;
import org.sqlcj.schema.Column;
import org.sqlcj.schema.Constraint;
import org.sqlcj.schema.ConstraintType;
import org.sqlcj.schema.DomainType;
import org.sqlcj.schema.EnumType;
import org.sqlcj.schema.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Applies one {@code CREATE TABLE} and parses the table it states: its
 * columns, in written order, those a {@code LIKE} or {@code PARTITION OF}
 * copies, the nullability each declaration leaves, and the column and table
 * constraints sqlcj models. It reads the parser it belongs to for the word
 * matching, the lookups, the diagnostics, and the column type mapping.
 */
final class CreateTableParser {

    /** The parser these tables are created for. */
    private final DefaultSchemaParser parser;

    CreateTableParser(DefaultSchemaParser parser) {
        this.parser = parser;
    }

    /**
     * Appends one table. A repeated table name fails, as PostgreSQL rejects it,
     * unless the statement declares {@code IF NOT EXISTS}. A clause that states
     * columns sqlcj cannot determine is rejected naming the clause.
     */
    void applyCreateTable(
        CreateTable createTable,
        int line,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables,
        SchemaNamespace namespace
    ) {
        String tableName = createTable.getTable().getUnquotedName();

        if (parser.support.indexOfTable(tables, tableName) >= 0) {
            if (createTable.isIfNotExists()) {
                return;
            }

            throw parser.support.tableAlreadyExists(tableName);
        }

        rejectUnsupportedCreateTableClause(createTable, line);

        tables.add(parseTable(createTable, line, enums, domains, tables, namespace));
    }

    /**
     * Rejects the {@code CREATE TABLE} clauses whose columns stand nowhere in
     * the schema source: a query, a composite type, and an inherited table,
     * whose parent's columns PostgreSQL copies and keeps following.
     */
    private void rejectUnsupportedCreateTableClause(CreateTable createTable, int line) {
        if (createTable.getSelect() != null) {
            throw parser.support.unsupportedCreateTableClause("AS SELECT", line);
        }

        if (createTable.getOfType() != null) {
            throw parser.support.unsupportedCreateTableClause("OF " + createTable.getOfType(), line);
        }

        if (inheritsAnotherTable(createTable)) {
            throw parser.support.unsupportedCreateTableClause("INHERITS", line);
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
     * <p>A table-level {@code PRIMARY KEY} is read once every element has been,
     * so it marks the columns it lists non-null wherever they come from, the
     * element list, a {@code LIKE}, or a partition's parent.
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
        List<Table> tables,
        SchemaNamespace namespace
    ) {
        String tableName = createTable.getTable().getUnquotedName();

        List<Column> columns = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();
        String partitionOf = null;

        if (createTable.getPartitionOf() != null) {
            Table parent = referencedTable(tables, createTable.getPartitionOf(), namespace);

            partitionOf = parent.name();
            columns.addAll(parent.columns());
        }

        List<TableElement> elements = createTable.getTableElements();

        if (elements == null) {
            if (partitionOf == null && !statesEmptyElementList(createTable)) {
                throw parser.support.unsupportedOpening("CREATE TABLE", line);
            }
        } else {
            for (TableElement element : elements) {
                if (element instanceof ColumnDefinition definition) {
                    addColumn(
                        columns,
                        tableName,
                        parseColumn(definition, enums, domains, namespace)
                    );
                    constraints.addAll(parseColumnConstraints(definition));
                } else if (element instanceof LikeClause like) {
                    Table source = referencedTable(tables, like.getTable(), namespace);

                    for (Column column : source.columns()) {
                        addColumn(columns, tableName, column);
                    }
                }
            }
        }

        List<Constraint> tableConstraints = parseTableConstraints(createTable);

        markPrimaryKeyColumnsNonNull(columns, tableConstraints);
        constraints.addAll(tableConstraints);

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
        net.sf.jsqlparser.schema.Table referenced,
        SchemaNamespace namespace
    ) {
        int index = parser.support.indexOfReferencedTable(tables, referenced, namespace);

        if (index < 0) {
            throw parser.support.tableNotFound(
                namespace.declaredName(referenced),
                namespace.isModeled(referenced)
                    ? tables
                    : List.of()
            );
        }

        return tables.get(index);
    }

    /**
     * Marks every column a {@code PRIMARY KEY} constraint lists non-null, in
     * its position, because PostgreSQL sets {@code NOT NULL} on a key column.
     * A listed column the table does not have is left alone, as the constraint
     * itself is recorded as written.
     */
    private void markPrimaryKeyColumnsNonNull(List<Column> columns, List<Constraint> constraints) {
        for (Constraint constraint : constraints) {
            if (constraint.type() != ConstraintType.PRIMARY_KEY) {
                continue;
            }

            for (String columnName : constraint.columns()) {
                int index = parser.support.indexOfColumn(columns, columnName);

                if (index >= 0) {
                    columns.set(index, parser.support.withNullability(columns.get(index), false));
                }
            }
        }
    }

    private void addColumn(List<Column> columns, String tableName, Column column) {
        if (parser.support.indexOfColumn(columns, column.name()) >= 0) {
            throw parser.support.columnAlreadyExists(tableName, column.name());
        }

        columns.add(column);
    }

    /**
     * Parses one column, whose declared type the shared column type mapping
     * resolves or records with its declared type text instead of failing the
     * schema.
     */
    Column parseColumn(
        ColumnDefinition definition,
        List<EnumType> enums,
        List<DomainType> domains,
        SchemaNamespace namespace
    ) {
        ColumnTypeMapping.MappedType mappedType = parser.columnTypeMapping.map(
            definition.getColDataType(),
            enums,
            domains,
            namespace
        );

        return new Column(
            columnName(definition),
            mappedType.type(),
            isNullableColumn(definition, mappedType.typeName()),
            mappedType.unsupportedType(),
            mappedType.enumType(),
            mappedType.array(),
            mappedType.blankPadded(),
            SchemaNamespace.storedName(definition.getColumnName())
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

    /**
     * Reports whether one column declaration leaves the column nullable.
     * PostgreSQL sets {@code NOT NULL} on a serial column, on a column
     * declared {@code PRIMARY KEY}, and on an identity column, so a column
     * that states any of them is non-null however it is written.
     */
    private boolean isNullableColumn(ColumnDefinition definition, String typeName) {
        return !isSerial(typeName)
            && !statesPrimaryKey(definition.getColumnSpecs())
            && !statesIdentity(definition.getColumnSpecs())
            && isNullable(definition);
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

    /**
     * Reports whether a column's specs state a column-level
     * {@code PRIMARY KEY}, which the parser reports as the two words
     * {@link #parseColumnConstraints} records the constraint from.
     */
    private boolean statesPrimaryKey(List<String> specs) {
        if (specs == null) {
            return false;
        }

        for (int i = 0; i < specs.size(); i++) {
            if (parser.support.statesWords(specs, i, "PRIMARY", "KEY")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Reports whether a column's specs state an identity column,
     * {@code GENERATED ALWAYS AS IDENTITY} or
     * {@code GENERATED BY DEFAULT AS IDENTITY}.
     * The parser reports the whole identity spec, with its options, as one
     * spec, so the specs are lexed with the parser's own lexer before their
     * words are compared. A generated column,
     * {@code GENERATED ALWAYS AS (<expression>) STORED}, states its expression
     * after {@code AS} instead of {@code IDENTITY} and is not one.
     */
    private boolean statesIdentity(List<String> specs) {
        if (specs == null) {
            return false;
        }

        List<String> tokens = SchemaSourceSplitter.tokens(String.join(" ", specs));

        for (int i = 0; i < tokens.size(); i++) {
            if (
                parser.support.statesWords(tokens, i, "GENERATED", "ALWAYS", "AS", "IDENTITY")
                    || parser.support.statesWords(tokens, i, "GENERATED", "BY", "DEFAULT", "AS", "IDENTITY")
            ) {
                return true;
            }
        }

        return false;
    }

    List<Constraint> parseColumnConstraints(ColumnDefinition definition) {
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
