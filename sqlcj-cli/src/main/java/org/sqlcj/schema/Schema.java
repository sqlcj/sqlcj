package org.sqlcj.schema;

import java.util.List;

public record Schema(
    List<Table> tables,
    List<EnumType> enums,
    List<DomainType> domains,
    List<View> views
) {

    public Schema {
        tables = List.copyOf(tables);
        enums = List.copyOf(enums);
        domains = List.copyOf(domains);
        views = List.copyOf(views);
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
}
