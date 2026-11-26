package org.sqlcj.schema.parser;

import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterExpressionPartition;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.drop.Drop;
import org.sqlcj.schema.Column;
import org.sqlcj.schema.Constraint;
import org.sqlcj.schema.DomainType;
import org.sqlcj.schema.EnumType;
import org.sqlcj.schema.Table;
import org.sqlcj.schema.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies the {@code ALTER TABLE} and {@code DROP TABLE} statements of a
 * schema source: the added, dropped, renamed, retyped, and renullified
 * columns of one table and of every partition that follows it, the actions
 * that leave a modeled column alone, the partition links, and the tables one
 * statement drops. It reads the parser it belongs to for the word matching,
 * the lookups, the diagnostics, the column parsing of a created table, and
 * the view updates a dropped {@code NOT NULL} makes.
 */
final class AlterTableParser {

    /** The parser these tables are altered for. */
    private final DefaultSchemaParser parser;

    AlterTableParser(DefaultSchemaParser parser) {
        this.parser = parser;
    }

    /**
     * Removes every named table, and every partition of one, keeping the order
     * of the remaining ones. A name a preceding name of the same statement
     * already removed as a partition of it is left alone, because it is dropped
     * either way.
     */
    void applyDropTable(Drop drop, List<Table> tables, SchemaNamespace namespace) {
        for (String tableName : droppedTableNames(drop, tables, namespace)) {
            int index = parser.support.indexOfTable(tables, tableName);

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
    private List<String> droppedTableNames(
        Drop drop,
        List<Table> tables,
        SchemaNamespace namespace
    ) {
        List<String> names = new ArrayList<>();

        for (net.sf.jsqlparser.schema.Table dropped : drop.getNames()) {
            if (!namespace.isModeled(dropped)) {
                continue;
            }

            String tableName = dropped.getUnquotedName();

            if (parser.support.indexOfTable(tables, tableName) < 0) {
                if (drop.isIfExists()) {
                    continue;
                }

                throw parser.support.tableNotFound(tableName, tables);
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
     *
     * <p>A statement that drops a {@code NOT NULL} marks every view recorded
     * before it, once its actions are applied; see
     * {@link ViewParser#markViewColumnsNullable(List)}.
     */
    void applyAlterTable(
        Alter alter,
        int line,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables,
        List<View> views,
        SchemaNamespace namespace
    ) {
        String tableName = alter.getTable().getUnquotedName();

        int index = parser.support.indexOfTable(tables, tableName);

        if (index < 0) {
            if (alter.isUseTableIfExists()) {
                return;
            }

            throw parser.support.tableNotFound(tableName, tables);
        }

        String schemaName = soleSetSchemaAction(alter);

        if (schemaName != null) {
            moveTableToSchema(line, schemaName, tables, index, namespace);

            return;
        }

        for (AlterExpression expression : alter.getAlterExpressions()) {
            applyToTableAndPartitions(line, expression, enums, domains, tables, index, namespace);
        }

        if (alter.getAlterExpressions().stream().anyMatch(this::dropsNotNull)) {
            parser.viewParser.markViewColumnsNullable(views);
        }
    }

    /**
     * Reports whether one {@code ALTER TABLE} action is an
     * {@code ALTER COLUMN ... DROP NOT NULL}, which makes a column of a
     * modeled table nullable.
     */
    private boolean dropsNotNull(AlterExpression expression) {
        return expression.getOperation() == AlterOperation.ALTER
            && parser.support.isNotEmpty(expression.getColumnDropNotNullList());
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

        return tokens.size() == 3 && parser.support.statesWords(tokens, 0, "SET", "SCHEMA")
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
    private void moveTableToSchema(
        int line,
        String schemaName,
        List<Table> tables,
        int index,
        SchemaNamespace namespace
    ) {
        if (namespace.isModeled(schemaName)) {
            return;
        }

        if (indexOfPartition(tables, tables.get(index).name()) >= 0) {
            throw parser.support.unsupportedAlterTableAction(line, "SET SCHEMA " + schemaName);
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
        int index,
        SchemaNamespace namespace
    ) {
        String tableName = tables.get(index).name();

        tables.set(
            index,
            applyAlterExpression(line, expression, enums, domains, tables, index, namespace)
        );

        if (!propagatesToPartitions(expression)) {
            return;
        }

        for (int i = 0; i < tables.size(); i++) {
            String parent = tables.get(i).partitionOf();

            if (parent != null && parent.equalsIgnoreCase(tableName)) {
                applyToTableAndPartitions(line, expression, enums, domains, tables, i, namespace);
            }
        }
    }

    /**
     * Reports whether one {@code ALTER TABLE} action changes a modeled column
     * and therefore reaches the partitions of its table: {@code ADD COLUMN},
     * {@code DROP COLUMN}, {@code RENAME COLUMN}, a new column type,
     * {@code SET NOT NULL}, {@code DROP NOT NULL}, and an added
     * {@code PRIMARY KEY}, which PostgreSQL applies to every partition as well.
     */
    private boolean propagatesToPartitions(AlterExpression expression) {
        AlterOperation operation = expression.getOperation();

        if (operation == AlterOperation.ADD) {
            return parser.support.isNotEmpty(expression.getColDataTypeList()) || addsPrimaryKey(expression);
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

        if (
            parser.support.isNotEmpty(expression.getColumnSetDefaultList())
                || parser.support.isNotEmpty(expression.getColumnDropDefaultList())
        ) {
            return false;
        }

        if (parser.support.isNotEmpty(expression.getColDataTypeList())) {
            return statesNewTypes(expression.getColDataTypeList());
        }

        return parser.support.isNotEmpty(expression.getColumnSetNotNullList())
            || parser.support.isNotEmpty(expression.getColumnDropNotNullList());
    }

    /**
     * Applies one {@code ALTER TABLE} action and returns the altered table.
     *
     * <p>An action that cannot change a modeled column's existence, name, type,
     * or nullability is accepted and leaves the table unchanged. An added
     * {@code PRIMARY KEY} whose columns the parser reads marks them non-null
     * and records no constraint. An action that
     * would change a column in a way sqlcj does not model, and an action sqlcj
     * does not recognize, is rejected naming the action's own SQL.
     */
    private Table applyAlterExpression(
        int line,
        AlterExpression expression,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables,
        int index,
        SchemaNamespace namespace
    ) {
        Table table = tables.get(index);
        AlterOperation operation = expression.getOperation();

        if (operation == AlterOperation.ADD && parser.support.isNotEmpty(expression.getColDataTypeList())) {
            return addColumns(expression, enums, domains, table, namespace);
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
            return alterColumns(line, expression, enums, domains, table, namespace);
        }

        if (operation == AlterOperation.ATTACH_PARTITION || operation == AlterOperation.DETACH_PARTITION) {
            return changePartitionLink(expression, tables, table, namespace);
        }

        if (addsPrimaryKey(expression)) {
            return changeNullability(expression.getIndex().getColumnsNames(), false, table);
        }

        if (isIgnoredConstraintAction(expression) || isIgnoredTableAction(operation)) {
            return table;
        }

        if (operation == AlterOperation.UNSPECIFIC) {
            return ignoreUnspecificActions(line, expression, table);
        }

        throw parser.support.unsupportedAlterTableAction(line, expression);
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
        Table table,
        SchemaNamespace namespace
    ) {
        if (!(expression instanceof AlterExpressionPartition partition)) {
            return table;
        }

        int index = parser.support.indexOfReferencedTable(tables, partition.getPartitionTable(), namespace);

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
            throw parser.support.unsupportedAlterTableAction(line, expression);
        }

        for (List<String> action : parser.topLevelActions(SchemaSourceSplitter.tokens(specifier))) {
            if (!ignoresTableLevelAction(action)) {
                throw parser.support.unsupportedAlterTableAction(line, String.join(" ", action));
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
        return parser.support.statesWords(action, 0, "OWNER", "TO")
            || switchesTriggerOrRule(action)
            || parser.support.statesWords(action, 0, "VALIDATE", "CONSTRAINT")
            || parser.support.statesWords(action, 0, "REPLICA", "IDENTITY")
            || parser.support.statesWords(action, 0, "CLUSTER", "ON")
            || parser.support.statesWords(action, 0, "SET", "WITHOUT", "CLUSTER")
            || parser.support.statesWords(action, 0, "SET", "WITHOUT", "OIDS")
            || parser.support.statesWords(action, 0, "SET", "(")
            || parser.support.statesWords(action, 0, "RESET", "(")
            || parser.support.statesWords(action, 0, "SET", "TABLESPACE")
            || parser.support.statesWords(action, 0, "SET", "LOGGED")
            || parser.support.statesWords(action, 0, "SET", "UNLOGGED")
            || parser.support.statesWords(action, 0, "SET", "ACCESS", "METHOD")
            || parser.support.statesWords(action, 0, "INHERIT")
            || parser.support.statesWords(action, 0, "NO", "INHERIT")
            || parser.support.statesWords(action, 0, "OF")
            || parser.support.statesWords(action, 0, "NOT", "OF");
    }

    /**
     * Reports whether one action switches a trigger or a rule:
     * {@code ENABLE} or {@code DISABLE}, and {@code ENABLE REPLICA} or
     * {@code ENABLE ALWAYS}, of either object.
     */
    private boolean switchesTriggerOrRule(List<String> action) {
        for (String object : List.of("TRIGGER", "RULE")) {
            if (
                parser.support.statesWords(action, 0, "ENABLE", object)
                    || parser.support.statesWords(action, 0, "DISABLE", object)
                    || parser.support.statesWords(action, 0, "ENABLE", "REPLICA", object)
                    || parser.support.statesWords(action, 0, "ENABLE", "ALWAYS", object)
            ) {
                return true;
            }
        }

        return false;
    }

    /**
     * Reports whether one {@code ALTER TABLE} action adds a {@code PRIMARY KEY}
     * whose columns the parser reads,
     * {@code ADD [CONSTRAINT <name>] PRIMARY KEY (<columns>)}, which PostgreSQL
     * sets {@code NOT NULL} on. A key form that states no column sqlcj reads is
     * not one and stays ignored, so its columns keep the nullability their own
     * declarations state.
     */
    private boolean addsPrimaryKey(AlterExpression expression) {
        if (
            expression.getOperation() != AlterOperation.ADD
                || parser.support.isNotEmpty(expression.getColDataTypeList())
        ) {
            return false;
        }

        Index index = expression.getIndex();

        return index != null
            && index.getKind() == Index.Kind.PRIMARY_KEY
            && parser.support.isNotEmpty(index.getColumnsNames());
    }

    /**
     * Reports whether one {@code ALTER TABLE} action states a constraint sqlcj
     * accepts without modeling it: adding a unique, foreign-key, or check
     * constraint or a {@code PRIMARY KEY} whose columns it does not read,
     * dropping a named constraint, or renaming one. The table itself is still
     * resolved, so a missing table fails as it does for any other
     * {@code ALTER TABLE}.
     *
     * <p>Dropping a key's constraint leaves its columns non-null, as PostgreSQL
     * leaves them; only {@code DROP NOT NULL} makes such a column nullable
     * again.
     */
    private boolean isIgnoredConstraintAction(AlterExpression expression) {
        AlterOperation operation = expression.getOperation();

        if (operation == AlterOperation.ADD) {
            return !parser.support.isNotEmpty(expression.getColDataTypeList())
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
        Table table,
        SchemaNamespace namespace
    ) {
        if (
            parser.support.isNotEmpty(expression.getColumnSetDefaultList())
                || parser.support.isNotEmpty(expression.getColumnDropDefaultList())
        ) {
            return table;
        }

        if (parser.support.isNotEmpty(expression.getColDataTypeList())) {
            if (statesNewTypes(expression.getColDataTypeList())) {
                return changeColumnTypes(expression, enums, domains, table, namespace);
            }

            if (ignoresColumnActions(expression.getColDataTypeList())) {
                return table;
            }

            throw parser.support.unsupportedAlterTableAction(line, expression);
        }

        if (parser.support.isNotEmpty(expression.getColumnSetNotNullList())) {
            return changeNullability(
                expression.getColumnSetNotNullList().stream()
                    .map(AlterExpression.ColumnSetNotNull::getColumnName)
                    .toList(),
                false,
                table
            );
        }

        if (parser.support.isNotEmpty(expression.getColumnDropNotNullList())) {
            return changeNullability(
                expression.getColumnDropNotNullList().stream()
                    .map(AlterExpression.ColumnDropNotNull::getColumnName)
                    .toList(),
                true,
                table
            );
        }

        throw parser.support.unsupportedAlterTableAction(line, expression);
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
     * change a column's existence, name, type, or nullability: PostgreSQL
     * requires a column to be {@code NOT NULL} already before
     * {@code ADD GENERATED ... AS IDENTITY} and leaves it {@code NOT NULL}
     * after {@code DROP IDENTITY}. The parser reports each of them in the entry
     * list of the action, without a declared type.
     */
    private boolean ignoresColumnAction(AlterExpression.ColumnDataType definition) {
        if (definition.isWithType()) {
            return false;
        }

        if (parser.support.isNotEmpty(definition.getIdentityAlterations())) {
            return true;
        }

        if (definition.getColDataType() == null) {
            return parser.support.statesWords(definition.getColumnSpecs(), 0, "DROP", "EXPRESSION");
        }

        if (!"SET".equalsIgnoreCase(definition.getColDataType().getDataType())) {
            return false;
        }

        return parser.support.statesWords(definition.getColumnSpecs(), 0, "STATISTICS")
            || parser.support.statesWords(definition.getColumnSpecs(), 0, "STORAGE")
            || parser.support.statesWords(definition.getColumnSpecs(), 0, "COMPRESSION");
    }

    /**
     * Appends each added column, typed exactly as a {@code CREATE TABLE} column
     * of the same declaration, and records its column-level constraints.
     */
    private Table addColumns(
        AlterExpression expression,
        List<EnumType> enums,
        List<DomainType> domains,
        Table table,
        SchemaNamespace namespace
    ) {
        List<Column> columns = new ArrayList<>(table.columns());
        List<Constraint> constraints = new ArrayList<>(table.constraints());

        for (AlterExpression.ColumnDataType definition : expression.getColDataTypeList()) {
            Column column = parser.createTableParser.parseColumn(definition, enums, domains, namespace);

            if (parser.support.indexOfColumn(columns, column.name()) >= 0) {
                if (expression.isUseIfNotExists()) {
                    continue;
                }

                throw parser.support.columnAlreadyExists(table.name(), column.name());
            }

            columns.add(column);
            constraints.addAll(parser.createTableParser.parseColumnConstraints(definition));
        }

        return new Table(table.name(), columns, constraints, table.partitionOf());
    }

    /** Removes one column and every constraint that lists it. */
    private Table dropColumn(AlterExpression expression, Table table) {
        String columnName = MultiPartName.unquote(expression.getColumnName());

        int index = parser.support.indexOfColumn(table.columns(), columnName);

        if (index < 0) {
            if (expression.isUsingIfExists()) {
                return table;
            }

            throw parser.support.columnNotFound(table.name(), columnName);
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

        int index = parser.support.indexOfColumn(table.columns(), oldName);

        if (index < 0) {
            throw parser.support.columnNotFound(table.name(), oldName);
        }

        int existing = parser.support.indexOfColumn(table.columns(), newName);

        if (existing >= 0 && existing != index) {
            throw parser.support.columnAlreadyExists(table.name(), newName);
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
                column.blankPadded(),
                SchemaNamespace.storedName(expression.getColumnName())
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

        int existing = parser.support.indexOfTable(tables, newName);

        if (existing >= 0 && existing != index) {
            throw parser.support.tableAlreadyExists(newName);
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
        Table table,
        SchemaNamespace namespace
    ) {
        List<Column> columns = new ArrayList<>(table.columns());

        for (AlterExpression.ColumnDataType definition : expression.getColDataTypeList()) {
            String columnName = MultiPartName.unquote(definition.getColumnName());

            int index = parser.support.indexOfColumn(columns, columnName);

            if (index < 0) {
                throw parser.support.columnNotFound(table.name(), columnName);
            }

            Column column = columns.get(index);
            Column retyped = parser.createTableParser.parseColumn(definition, enums, domains, namespace);

            columns.set(
                index,
                new Column(
                    column.name(),
                    retyped.type(),
                    column.nullable(),
                    retyped.unsupportedType(),
                    retyped.enumType(),
                    retyped.array(),
                    retyped.blankPadded(),
                    column.sqlName()
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

            int index = parser.support.indexOfColumn(columns, columnName);

            if (index < 0) {
                throw parser.support.columnNotFound(table.name(), columnName);
            }

            columns.set(index, parser.support.withNullability(columns.get(index), nullable));
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
}
