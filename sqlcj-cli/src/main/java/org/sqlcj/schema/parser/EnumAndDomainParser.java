package org.sqlcj.schema.parser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.AlterDomain;
import net.sf.jsqlparser.statement.alter.AlterType;
import net.sf.jsqlparser.statement.create.domain.CreateDomain;
import net.sf.jsqlparser.statement.create.type.CreateType;
import net.sf.jsqlparser.statement.create.type.EnumTypeDefinition;
import org.sqlcj.schema.Column;
import org.sqlcj.schema.DomainType;
import org.sqlcj.schema.EnumType;
import org.sqlcj.schema.Table;
import org.sqlcj.schema.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies the enum type and domain statements of a schema source: the labels
 * one {@code CREATE TYPE ... AS ENUM} declares, every {@code ALTER TYPE}
 * action sqlcj models, and the base type one {@code CREATE DOMAIN} is declared
 * over, readable or not, with the renames and the removals the schema's
 * domains follow. It reads the parser it belongs to for the word matching,
 * the lookups, the diagnostics, the column type mapping, and the rules for an
 * unreadable statement.
 */
final class EnumAndDomainParser {

    /** The parser these types are applied for. */
    private final DefaultSchemaParser parser;

    EnumAndDomainParser(DefaultSchemaParser parser) {
        this.parser = parser;
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
    void applyCreateEnumType(
        CreateType createType,
        EnumTypeDefinition definition,
        List<EnumType> enums,
        List<DomainType> domains,
        SchemaNamespace namespace
    ) {
        String typeName = namespace.modeledTypeName(createType.getName());

        if (typeName == null) {
            return;
        }

        removeDomain(domains, typeName);

        int index = parser.support.indexOfEnum(enums, typeName);

        List<String> labels = new ArrayList<>();

        for (StringValue declaredLabel : definition.getLabels()) {
            String label = declaredLabel.getNotExcapedValue();

            if (labels.contains(label)) {
                throw parser.support.labelAlreadyExists(typeName, label);
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
     * the one namespace the query group models, so a name qualified with that
     * schema resolves as its unqualified name and a type of any other schema is
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
    void applyAlterType(
        AlterType alterType,
        int line,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables,
        List<View> views,
        SchemaNamespace namespace
    ) {
        AlterType.Action action = alterType.getAction();

        if (ignoresAlterTypeAction(action)) {
            return;
        }

        String typeName = namespace.modeledTypeName(alterType.getName());

        if (typeName == null) {
            return;
        }

        if (action == AlterType.Action.ADD_VALUE) {
            applyAddEnumValue(alterType, typeName, enums);

            return;
        }

        int index = parser.support.indexOfEnum(enums, typeName);

        switch (action) {
            case RENAME_VALUE -> renameEnumLabel(alterType, typeName, index, enums);
            case RENAME -> renameType(
                MultiPartName.unquote(alterType.getNewName()),
                typeName,
                index,
                enums,
                domains,
                tables,
                views
            );
            case SET_SCHEMA -> moveEnumTypeToSchema(alterType, line, index, namespace);
            default -> throw parser.support.unsupportedAlterTypeAction(String.valueOf(action), line);
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
            throw parser.support.typeNotFound(typeName);
        }

        EnumType enumType = enums.get(index);
        String label = alterType.getValue().getNotExcapedValue();
        String newLabel = alterType.getNewValue().getNotExcapedValue();

        int position = enumType.labels().indexOf(label);

        if (position < 0) {
            throw parser.support.labelNotFound(typeName, label);
        }

        if (enumType.labels().contains(newLabel)) {
            throw parser.support.labelAlreadyExists(typeName, newLabel);
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
        List<Table> tables,
        List<View> views
    ) {
        int domain = indexOfDomain(domains, typeName);

        if (domain >= 0) {
            renameDomain(newName, domain, domains);

            return;
        }

        renameEnumType(newName, typeName, index, enums, domains, tables, views);
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
     *
     * <p>The rename is recorded with every view recorded before it as well,
     * because PostgreSQL renames the type of a view's columns with it.
     */
    private void renameEnumType(
        String newName,
        String typeName,
        int index,
        List<EnumType> enums,
        List<DomainType> domains,
        List<Table> tables,
        List<View> views
    ) {
        if (index < 0) {
            return;
        }

        parser.viewParser.recordViewEnumRename(views, typeName, newName);

        int replaced = parser.support.indexOfEnum(enums, newName);
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
    private void moveEnumTypeToSchema(
        AlterType alterType,
        int line,
        int index,
        SchemaNamespace namespace
    ) {
        if (index < 0 || namespace.isModeled(alterType.getNewName())) {
            return;
        }

        throw parser.support.unsupportedAlterTypeAction("SET SCHEMA " + alterType.getNewName(), line);
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
                    column.blankPadded(),
                    column.sqlName()
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
        int index = parser.support.indexOfEnum(enums, typeName);

        if (index < 0) {
            throw parser.support.typeNotFound(typeName);
        }

        EnumType enumType = enums.get(index);
        String label = alterType.getValue().getNotExcapedValue();

        if (enumType.labels().contains(label)) {
            if (alterType.isIfNotExists()) {
                return;
            }

            throw parser.support.labelAlreadyExists(typeName, label);
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
            throw parser.support.labelNotFound(enumType.name(), neighbor);
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
    void applyCreateDomain(
        CreateDomain createDomain,
        List<EnumType> enums,
        List<DomainType> domains,
        SchemaNamespace namespace
    ) {
        String typeName = namespace.modeledTypeName(createDomain.getName());

        if (typeName == null) {
            return;
        }

        ColumnTypeMapping.MappedType base = parser.columnTypeMapping.map(
            createDomain.getDataType(),
            enums,
            domains,
            namespace
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
    void applyUnreadableCreateDomain(
        SchemaSourceSplitter.SourceStatement statement,
        JSQLParserException failure,
        List<EnumType> enums,
        List<DomainType> domains,
        SchemaNamespace namespace
    ) {
        List<String> tokens = statement.tokens();

        if (parser.namesAnotherSchema("CREATE DOMAIN", tokens, namespace)) {
            return;
        }

        int nameEnd = parser.support.afterTypeName(tokens, 2);

        if (nameEnd < 0) {
            if (failure != null) {
                throw parser.unreadableStatement(statement, failure);
            }

            throw parser.support.unsupportedOpening("CREATE DOMAIN", statement.line());
        }

        String typeName = namespace.modeledTypeName(parser.support.declaredName(tokens, 2, nameEnd));

        if (typeName == null) {
            return;
        }

        CreateDomain createDomain = declaredCreateDomain(tokens, nameEnd);

        if (createDomain != null) {
            applyCreateDomain(createDomain, enums, domains, namespace);

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
        return switch (parser.support.upperCase(token)) {
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
    void applyAlterDomain(
        AlterDomain alterDomain,
        List<DomainType> domains,
        SchemaNamespace namespace
    ) {
        if (alterDomain.getAction() != AlterDomain.Action.RENAME) {
            return;
        }

        String typeName = namespace.modeledTypeName(alterDomain.getName());

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
    void applyDropDomain(
        SchemaSourceSplitter.SourceStatement statement,
        List<DomainType> domains,
        SchemaNamespace namespace
    ) {
        List<String> names = parser.support.droppedNames(statement.tokens(), 2);

        if (names == null) {
            throw parser.support.unsupportedOpening("DROP DOMAIN", statement.line());
        }

        for (String declaredName : names) {
            String typeName = namespace.modeledTypeName(declaredName);

            if (typeName != null) {
                removeDomain(domains, typeName);
            }
        }
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
}
