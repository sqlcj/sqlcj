package org.sqlcj.schema;

/**
 * One domain type declared by a schema source, which is a name for the type it
 * is declared over, so a column or a cast of the domain is the base type sqlcj
 * models.
 *
 * <p>A domain over a type sqlcj does not map carries a {@code null}
 * {@code type}, so a column of it is recorded with its declared type, as a
 * column of the unmapped base itself would be.
 *
 * @param name        the schema's declared name of the domain
 * @param type        the mapped type of the domain's base, and {@code null}
 *                    when sqlcj does not map that base
 * @param enumType    the schema's declared name of the enum type the base
 *                    names when {@code type} is {@link ColumnType#ENUM}, and
 *                    {@code null} otherwise
 * @param array       whether the base is a one-dimensional array of
 *                    {@code type}
 * @param blankPadded whether the base's declared spelling is the blank-padded
 *                    character type
 */
public record DomainType(
    String name,
    ColumnType type,
    String enumType,
    boolean array,
    boolean blankPadded
) {
}
