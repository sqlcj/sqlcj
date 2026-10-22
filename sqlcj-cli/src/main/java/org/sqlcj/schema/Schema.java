package org.sqlcj.schema;

import java.util.List;

public record Schema(
    List<Table> tables,
    List<EnumType> enums,
    List<DomainType> domains
) {

    public Schema {
        tables = List.copyOf(tables);
        enums = List.copyOf(enums);
        domains = List.copyOf(domains);
    }

    /** A schema that declares no enum type and no domain. */
    public Schema(List<Table> tables) {
        this(tables, List.of());
    }

    /** A schema that declares no domain. */
    public Schema(List<Table> tables, List<EnumType> enums) {
        this(tables, enums, List.of());
    }
}
