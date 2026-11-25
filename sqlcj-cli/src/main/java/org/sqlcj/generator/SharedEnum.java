package org.sqlcj.generator;

import java.util.List;
import java.util.Locale;

/**
 * One Java enum of the package, generated once from the group that first
 * used the enum type it is generated from.
 */
record SharedEnum(
    String groupName,
    String enumName,
    String typeName,
    List<String> labels,
    List<String> constantNames
) {

    /**
     * Two uses are the same Java enum when they generate the same enum type
     * from the same enum type name, compared as a case-insensitive
     * filesystem compares the type's generated file.
     */
    String identity() {
        return typeName + "\n" + enumName.toLowerCase(Locale.ROOT);
    }

    String portabilityKey() {
        return typeName.toLowerCase(Locale.ROOT);
    }
}
