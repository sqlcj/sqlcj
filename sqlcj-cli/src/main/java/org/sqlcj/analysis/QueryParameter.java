package org.sqlcj.analysis;

import org.sqlcj.schema.ColumnType;

/**
 * One analyzed parameter occurrence.
 *
 * @param enumType the schema's declared name of the parameter's enum type when
 *                 {@code type} is {@link ColumnType#ENUM}, and {@code null}
 *                 otherwise
 * @param array    whether the parameter is a one-dimensional array of
 *                 {@code type}, whose Java type is a list of the element's Java
 *                 type
 * @param blankPadded whether the parameter's column is declared with the
 *                    blank-padded {@code CHAR} or {@code CHARACTER} spelling of
 *                    {@link ColumnType#VARCHAR}, which PostgreSQL names
 *                    {@code bpchar}
 * @param nullable    whether the query gives a {@code null} bound at this
 *                    occurrence a meaning, which a null test of the occurrence
 *                    and a nullable column the occurrence is written into both
 *                    do, and every other position does not
 */
public record QueryParameter(
    int index,
    String name,
    ColumnType type,
    String enumType,
    boolean array,
    boolean blankPadded,
    boolean nullable
) {

    /** A parameter of a type whose Java type is decided by {@code type} alone. */
    public QueryParameter(int index, String name, ColumnType type) {
        this(index, name, type, null);
    }

    /** A scalar parameter, which may be of an enum type. */
    public QueryParameter(int index, String name, ColumnType type, String enumType) {
        this(index, name, type, enumType, false);
    }

    /** A parameter whose declared spelling is not blank padded. */
    public QueryParameter(
        int index,
        String name,
        ColumnType type,
        String enumType,
        boolean array
    ) {
        this(index, name, type, enumType, array, false);
    }

    /** A parameter a {@code null} argument has no meaning for. */
    public QueryParameter(
        int index,
        String name,
        ColumnType type,
        String enumType,
        boolean array,
        boolean blankPadded
    ) {
        this(index, name, type, enumType, array, blankPadded, false);
    }

    /** The same parameter, nullable. */
    public QueryParameter asNullable() {
        return nullable
            ? this
            : new QueryParameter(index, name, type, enumType, array, blankPadded, true);
    }
}
