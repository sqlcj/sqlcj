package org.sqlcj.schema;

import java.util.List;

/**
 * One query group's schema model: the tables, enum types, domains, and views of
 * the one PostgreSQL namespace the group models.
 *
 * @param namespace the one modeled PostgreSQL schema, as the configuration
 *     names it, which is {@link #PUBLIC_NAMESPACE} when it names none
 */
public record Schema(
    List<Table> tables,
    List<EnumType> enums,
    List<DomainType> domains,
    List<View> views,
    String namespace
) {

    /** The namespace a query group models when its configuration names none. */
    public static final String PUBLIC_NAMESPACE = "public";

    public Schema {
        tables = List.copyOf(tables);
        enums = List.copyOf(enums);
        domains = List.copyOf(domains);
        views = List.copyOf(views);
    }

    /** An empty schema of one modeled namespace. */
    public Schema(String namespace) {
        this(List.of(), List.of(), List.of(), List.of(), namespace);
    }

    /** A schema that declares no enum type, no domain, and no view. */
    public Schema(List<Table> tables) {
        this(tables, List.of());
    }

    /** A schema that declares no domain and no view. */
    public Schema(List<Table> tables, List<EnumType> enums) {
        this(tables, enums, List.of());
    }

    /** A schema that records no view. */
    public Schema(List<Table> tables, List<EnumType> enums, List<DomainType> domains) {
        this(tables, enums, domains, List.of());
    }

    /** A schema of the default namespace, which is {@code public}. */
    public Schema(
        List<Table> tables,
        List<EnumType> enums,
        List<DomainType> domains,
        List<View> views
    ) {
        this(tables, enums, domains, views, PUBLIC_NAMESPACE);
    }
}
