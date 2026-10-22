package org.sqlcj.schema.parser;

import net.sf.jsqlparser.statement.create.table.ColDataType;
import org.sqlcj.schema.ColumnType;
import org.sqlcj.schema.DomainType;
import org.sqlcj.schema.EnumType;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps one declared SQL type to the type sqlcj models, against the enum types
 * and the domains a schema declares.
 *
 * <p>A schema column states its type in a column definition and a query states
 * it in a cast, so both resolve their type here and report an unmapped type
 * with the same text.
 */
public final class ColumnTypeMapping {

    /** One parenthesized type argument group, such as {@code (10, 2)}. */
    private static final Pattern TYPE_ARGUMENTS = Pattern.compile("\\([^)]*\\)");

    /** The array dimension groups a declared type text ends with. */
    private static final Pattern TRAILING_DIMENSIONS = Pattern.compile("(\\[[^\\[\\]]*\\])+$");

    /** The precision a {@code FLOAT} declaration states, as in {@code (24)}. */
    private static final Pattern FLOAT_PRECISION = Pattern.compile("\\(\\s*(\\d+)\\s*\\)");

    /**
     * One declared SQL type and the type sqlcj maps it to.
     *
     * @param type            the mapped type, and {@code null} for a declared
     *                        type sqlcj does not map
     * @param enumType        the schema's declared name of the enum type the
     *                        declaration names when {@code type} is
     *                        {@link ColumnType#ENUM}, and {@code null}
     *                        otherwise
     * @param array           whether the declaration is a one-dimensional
     *                        array of {@code type}
     * @param blankPadded     whether the declared spelling is the blank-padded
     *                        character type
     * @param unsupportedType the canonical declared type text of a type sqlcj
     *                        does not map, including its array dimensions, and
     *                        {@code null} for a mapped type
     * @param typeName        the canonical spelling of the declared element
     *                        type, without its array dimensions
     */
    public record MappedType(
        ColumnType type,
        String enumType,
        boolean array,
        boolean blankPadded,
        String unsupportedType,
        String typeName
    ) {

        /** Reports whether sqlcj maps the declared type. */
        public boolean mapped() {
            return type != null;
        }
    }

    /**
     * Maps one declared SQL type, recording a type sqlcj cannot map with its
     * declared type text instead of failing. A declaration of exactly one array
     * dimension is an array of its declared element type; a declaration of more
     * dimensions, and an array whose element type has no array mapping, is
     * recorded like any other unmapped type.
     *
     * <p>A type argument is ignored, except that a {@code FLOAT} precision
     * selects the type it maps to, as PostgreSQL defines it; see
     * {@link #floatType(String, int)}.
     *
     * <p>A built-in spelling is resolved before a domain and a domain before an
     * enum type, as PostgreSQL resolves a declared name: a domain is the type
     * it is declared over, so it carries that type's mapping; see
     * {@link #declaredDomain(String, List)}.
     */
    public MappedType map(
        ColDataType declaredType,
        List<EnumType> enums,
        List<DomainType> domains
    ) {
        String elementText = elementText(declaredType.getDataType());
        String typeName = typeName(elementText);
        int arrayDimensions = arrayDimensions(declaredType);
        boolean array = arrayDimensions == 1;

        if (typeName.equals("FLOAT")) {
            return floatType(elementText, arrayDimensions);
        }

        if (arrayDimensions <= 1) {
            ColumnType type = columnType(typeName);

            if (type == null) {
                type = extensionType(elementText);
            }

            if (type != null) {
                if (!array || isArrayElementType(type)) {
                    return new MappedType(
                        type,
                        null,
                        array,
                        isBlankPadded(typeName),
                        null,
                        typeName
                    );
                }
            } else {
                DomainType domain = declaredDomain(elementText, domains);

                if (domain != null) {
                    if (!array && domain.type() != null) {
                        return new MappedType(
                            domain.type(),
                            domain.enumType(),
                            domain.array(),
                            domain.blankPadded(),
                            null,
                            typeName
                        );
                    }

                    return unmappedType(typeName, arrayDimensions);
                }

                EnumType enumType = declaredEnum(elementText, enums);

                if (enumType != null) {
                    return new MappedType(
                        ColumnType.ENUM,
                        enumType.name(),
                        array,
                        false,
                        null,
                        typeName
                    );
                }
            }
        }

        return unmappedType(typeName, arrayDimensions);
    }

    /** A declaration sqlcj does not map, recorded with its declared text. */
    private MappedType unmappedType(String typeName, int arrayDimensions) {
        return new MappedType(
            null,
            null,
            false,
            false,
            typeName + "[]".repeat(arrayDimensions),
            typeName
        );
    }

    /**
     * Maps a declared {@code FLOAT}, whose precision selects the type it maps
     * to, as PostgreSQL defines it: no precision and a precision of
     * {@code 25} through {@code 53} are {@code DOUBLE PRECISION}, and a
     * precision of {@code 1} through {@code 24} is {@code REAL}. Both map as
     * their own spellings do, an array of one dimension included.
     *
     * <p>Any other precision, which PostgreSQL rejects, is unmapped and is
     * recorded with its own precision, as in {@code FLOAT(54)}, because
     * {@code FLOAT} alone names a mapped type.
     */
    private MappedType floatType(String elementText, int arrayDimensions) {
        String precision = floatPrecision(elementText);

        ColumnType type = precision == null
            ? ColumnType.DOUBLE_PRECISION
            : floatPrecisionType(precision);

        if (type != null) {
            if (arrayDimensions <= 1) {
                return new MappedType(type, null, arrayDimensions == 1, false, null, "FLOAT");
            }

            return unmappedFloat("FLOAT", arrayDimensions);
        }

        return unmappedFloat("FLOAT(%s)".formatted(precision), arrayDimensions);
    }

    /** A {@code FLOAT} declaration sqlcj does not map, under a recorded text. */
    private MappedType unmappedFloat(String recordedName, int arrayDimensions) {
        return new MappedType(
            null,
            null,
            false,
            false,
            recordedName + "[]".repeat(arrayDimensions),
            "FLOAT"
        );
    }

    /**
     * The precision a declared {@code FLOAT} element type states, or
     * {@code null} when it states none.
     */
    private String floatPrecision(String elementText) {
        Matcher matcher = FLOAT_PRECISION.matcher(elementText);

        return matcher.find()
            ? matcher.group(1)
            : null;
    }

    /**
     * The type a declared {@code FLOAT} precision selects, or {@code null} for
     * a precision PostgreSQL rejects. A precision of more than two digits is
     * out of range in any case, so a declared precision never has to fit an
     * {@code int}.
     */
    private ColumnType floatPrecisionType(String precision) {
        if (precision.length() > 2) {
            return null;
        }

        int bits = Integer.parseInt(precision);

        if (bits >= 1 && bits <= 24) {
            return ColumnType.REAL;
        }

        return bits >= 25 && bits <= 53
            ? ColumnType.DOUBLE_PRECISION
            : null;
    }

    /**
     * Reports whether a declared spelling is the blank-padded character type,
     * which PostgreSQL names {@code bpchar} and maps like {@code VARCHAR}. The
     * distinction is kept because the two names are not interchangeable where
     * PostgreSQL resolves an array type from its element's name.
     */
    private boolean isBlankPadded(String typeName) {
        return switch (typeName) {
            case "CHAR", "CHARACTER" -> true;
            default -> false;
        };
    }

    /**
     * Reports whether an array of a mapped type is mapped as well.
     * {@code BYTEA}, {@code JSON}, and {@code JSONB} arrays are recorded with
     * their declared type instead, because their elements are bound and read as
     * text or bytes rather than as a value of a mapped element type.
     *
     * <p>Query analysis asks the same question of a column whose type an array
     * parameter carries, so the rule is stated once here.
     */
    public boolean isArrayElementType(ColumnType type) {
        return type != ColumnType.BYTEA
            && type != ColumnType.JSON
            && type != ColumnType.JSONB;
    }

    /**
     * The domain an unmapped declared element type names, or {@code null} when
     * the schema declares no such domain. The name is resolved in the one
     * namespace sqlcj models, exactly as an enum type's name is.
     *
     * <p>A declaration that names a domain never resolves as an enum type of
     * the same name, because PostgreSQL holds one type of a name at a time: an
     * array of a domain and a domain over a type sqlcj does not map are
     * recorded with their declared type rather than resolved further.
     */
    private DomainType declaredDomain(String elementText, List<DomainType> domains) {
        String typeName = SchemaNamespace.modeledTypeName(elementText);

        if (typeName == null) {
            return null;
        }

        return domains.stream()
            .filter(domain -> domain.name().equalsIgnoreCase(typeName))
            .findFirst()
            .orElse(null);
    }

    /**
     * The enum type an unmapped declared element type names, or {@code null}
     * when the schema declares no such type. The name is resolved in the one
     * namespace sqlcj models, so a {@code public}-qualified name resolves as
     * its unqualified name and a name of any other schema never resolves; see
     * {@link SchemaNamespace}.
     */
    private EnumType declaredEnum(String elementText, List<EnumType> enums) {
        String typeName = SchemaNamespace.modeledTypeName(elementText);

        if (typeName == null) {
            return null;
        }

        return enums.stream()
            .filter(enumType -> enumType.name().equalsIgnoreCase(typeName))
            .findFirst()
            .orElse(null);
    }

    /**
     * Reports how many array dimensions a declaration states. The parser
     * reports an array's dimensions separately from its element type, so a
     * declaration with any dimension is an array of the reported type, except
     * that a qualified element type carries its dimensions inside its own text,
     * as in {@code public.mood[]}.
     */
    private int arrayDimensions(ColDataType declaredType) {
        List<Integer> arrayData = declaredType.getArrayData();

        int dimensions = arrayData == null
            ? 0
            : arrayData.size();

        return dimensions + textDimensions(declaredType.getDataType());
    }

    /** How many array dimension groups a declared type text itself ends with. */
    private int textDimensions(String dataType) {
        Matcher matcher = TRAILING_DIMENSIONS.matcher(dataType);

        if (!matcher.find()) {
            return 0;
        }

        return (int) matcher.group().chars().filter(character -> character == '[').count();
    }

    /** The declared element type text, without its array dimension groups. */
    private String elementText(String dataType) {
        return TRAILING_DIMENSIONS.matcher(dataType).replaceAll("");
    }

    /**
     * Returns the canonical spelling of a declared element type: upper case,
     * without type arguments such as a length or a precision, and with single
     * spaces between the remaining words.
     */
    private String typeName(String elementText) {
        return TYPE_ARGUMENTS
            .matcher(elementText.toUpperCase(Locale.ROOT))
            .replaceAll(" ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** The mapped type of declared spelling, or {@code null} if unmapped. */
    private ColumnType columnType(String typeName) {
        return switch (typeName) {
            case "INTEGER", "INT", "INT4", "SERIAL", "SERIAL4" -> ColumnType.INTEGER;
            case "BIGINT", "INT8", "BIGSERIAL", "SERIAL8" -> ColumnType.BIGINT;
            case "SMALLINT", "INT2", "SMALLSERIAL", "SERIAL2" -> ColumnType.SMALLINT;
            case "BOOLEAN", "BOOL" -> ColumnType.BOOLEAN;
            case "VARCHAR", "CHARACTER VARYING", "CHAR", "CHARACTER" -> ColumnType.VARCHAR;
            case "TEXT" -> ColumnType.TEXT;
            case "DATE" -> ColumnType.DATE;
            case "TIME", "TIME WITHOUT TIME ZONE" -> ColumnType.TIME;
            case "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> ColumnType.TIMESTAMP;
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> ColumnType.TIMESTAMP_WITH_TIME_ZONE;
            case "DECIMAL", "NUMERIC" -> ColumnType.DECIMAL;
            case "REAL", "FLOAT4" -> ColumnType.REAL;
            case "DOUBLE PRECISION", "FLOAT8" -> ColumnType.DOUBLE_PRECISION;
            case "UUID" -> ColumnType.UUID;
            case "BYTEA" -> ColumnType.BYTEA;
            case "JSON" -> ColumnType.JSON;
            case "JSONB" -> ColumnType.JSONB;
            case "CITEXT" -> ColumnType.CITEXT;
            case "INET" -> ColumnType.INET;
            case "CIDR" -> ColumnType.CIDR;
            case "MACADDR" -> ColumnType.MACADDR;
            case "MACADDR8" -> ColumnType.MACADDR8;
            case "INTERVAL" -> ColumnType.INTERVAL;
            default -> null;
        };
    }

    /**
     * The mapped type a qualified declared element type names, or {@code null}
     * when it names none. An extension type belongs to the schema it was
     * installed in, which {@code pg_dump} writes as a qualifier, so
     * {@code citext} is resolved in the one namespace sqlcj models, exactly as
     * an enum type's name is: {@code public.citext} is the type {@code citext}
     * and a name qualified with any other schema never resolves. A built-in
     * type is never qualified by {@code pg_dump}, so only {@code citext} is
     * resolved this way.
     */
    private ColumnType extensionType(String elementText) {
        String modeledName = SchemaNamespace.modeledTypeName(elementText);

        if (modeledName == null) {
            return null;
        }

        return typeName(modeledName).equals("CITEXT")
            ? ColumnType.CITEXT
            : null;
    }
}
