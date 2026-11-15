package org.sqlcj.analysis;

import org.sqlcj.schema.ColumnType;
import org.sqlcj.type.DefaultTypeResolver;
import org.sqlcj.type.TypeResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parameter groups of one repository's analyzed queries, and the rule two
 * parameter occurrences are one Java type by.
 *
 * <p>One group name is one generated record per repository, so every query that
 * uses a group must give it the same fields, in any order, each of the same
 * Java type and the same nullness. The first query of the repository that uses
 * a group defines it, and every later use is compared with that query, which is
 * reported as the conflict's own earlier query.
 *
 * <p>The same-type rule is the one a repeated placeholder of a single query
 * already follows: an enum is the same type only as an occurrence of the same
 * enum type, an array is a list of its element's type, and every other
 * occurrence is compared by the Java type it maps to.
 */
public final class ParameterGroups {

    private final TypeResolver typeResolver = new DefaultTypeResolver();

    /**
     * The conflicts the analyzed queries of one repository state about their
     * parameter groups, in query order and at most one per group of one query,
     * which is the first difference that group's use states.
     */
    public List<Conflict> conflicts(List<QueryModel> queries) {
        Map<String, GroupUse> uses = new LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();

        for (int index = 0; index < queries.size(); index++) {
            for (Map.Entry<String, GroupUse> use : groupUses(queries.get(index), index).entrySet()) {
                GroupUse defined = uses.putIfAbsent(use.getKey(), use.getValue());

                if (defined == null) {
                    continue;
                }

                String detail = difference(defined, use.getValue());

                if (detail != null) {
                    conflicts.add(
                        new Conflict(index, defined.query(), use.getKey(), detail)
                    );
                }
            }
        }

        return List.copyOf(conflicts);
    }

    /**
     * Reports whether two occurrences of one logical parameter are the same
     * type. An enum occurrence is the same type only as an occurrence of the
     * same enum type, because each enum type generates a Java type of its own;
     * every other occurrence is compared by the Java type it resolves to. An
     * array occurrence is a list of its element's Java type, so it is never the
     * same type as an occurrence of that element.
     */
    boolean isSameType(QueryParameter parameter, QueryParameter occurrence) {
        if (parameter.array() != occurrence.array()) {
            return false;
        }

        if (parameter.type() == ColumnType.ENUM || occurrence.type() == ColumnType.ENUM) {
            return parameter.type() == occurrence.type()
                && parameter.enumType().equalsIgnoreCase(occurrence.enumType());
        }

        return typeResolver.resolve(parameter.type())
            .equals(typeResolver.resolve(occurrence.type()));
    }

    /**
     * Describes a parameter's type for a diagnostic. An enum is described by
     * its PostgreSQL name, because analysis renders no Java name, and an array
     * by the list of its element's description.
     */
    String describeType(QueryParameter parameter) {
        if (parameter.type() == ColumnType.ENUM) {
            return parameter.array()
                ? parameter.enumType() + "[]"
                : parameter.enumType();
        }

        String javaType = typeResolver.resolve(parameter.type());

        return parameter.array()
            ? "List<" + javaType + ">"
            : javaType;
    }

    /**
     * The groups one query uses, each with its fields in order of first
     * occurrence, keyed by the group as the query spells it and ordered by
     * first occurrence as well.
     */
    private Map<String, GroupUse> groupUses(QueryModel query, int index) {
        Map<String, GroupUse> uses = new LinkedHashMap<>();

        for (QueryParameter parameter : query.parameters()) {
            if (parameter.group() == null) {
                continue;
            }

            uses
                .computeIfAbsent(
                    parameter.group(),
                    group -> new GroupUse(index, new LinkedHashMap<>())
                )
                .fields()
                .putIfAbsent(parameter.name(), parameter);
        }

        return uses;
    }

    /**
     * The first difference between the defining use of one group and a later
     * one, and {@code null} when the two agree: the fields themselves, in any
     * order, and then, in the defining query's field order, each field's Java
     * type and its nullness.
     */
    private String difference(GroupUse defined, GroupUse used) {
        if (!fieldNames(defined).equals(fieldNames(used))) {
            return "fields (%s) there, (%s) here"
                .formatted(
                    String.join(", ", defined.fields().keySet()),
                    String.join(", ", used.fields().keySet())
                );
        }

        for (Map.Entry<String, QueryParameter> field : defined.fields().entrySet()) {
            QueryParameter parameter = field.getValue();
            QueryParameter occurrence = used.fields().get(field.getKey());

            if (!isSameType(parameter, occurrence)) {
                return "field %s is %s there, %s here"
                    .formatted(
                        field.getKey(),
                        describeType(parameter),
                        describeType(occurrence)
                    );
            }

            if (parameter.nullable() != occurrence.nullable()) {
                return "field %s is %s there, %s here"
                    .formatted(
                        field.getKey(),
                        describeNullness(parameter),
                        describeNullness(occurrence)
                    );
            }
        }

        return null;
    }

    private Set<String> fieldNames(GroupUse use) {
        return new LinkedHashSet<>(use.fields().keySet());
    }

    private String describeNullness(QueryParameter parameter) {
        return parameter.nullable() ? "nullable" : "non-null";
    }

    /**
     * One query's use of one parameter group: the query's position in the
     * repository's queries and the parameter of each of its fields, by the
     * field's SQL name and in order of first occurrence.
     */
    private record GroupUse(int query, Map<String, QueryParameter> fields) {
    }

    /**
     * One conflict between two queries of one repository over one parameter
     * group.
     *
     * @param query        the position of the query whose use conflicts, which
     *                     is the later of the two
     * @param earlierQuery the position of the query that defines the group,
     *                     which is the first one that uses it
     * @param group        the group as both queries spell it
     * @param detail       what differs, written for the conflict's own reason
     */
    public record Conflict(int query, int earlierQuery, String group, String detail) {

        /**
         * The reason of this conflict, which names the group, the query that
         * defines it, and where that query stands, so that both locations of
         * the conflict are reported.
         */
        public String reason(String earlierQueryName, int earlierLine) {
            return "parameter group '%s' differs from its use in query '%s' at line %d: %s; "
                .formatted(group, earlierQueryName, earlierLine, detail)
                + "rename one of the two groups";
        }
    }
}
