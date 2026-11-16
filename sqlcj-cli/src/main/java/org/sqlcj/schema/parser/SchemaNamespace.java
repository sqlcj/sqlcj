package org.sqlcj.schema.parser;

import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.schema.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * The one PostgreSQL namespace a query group models, which is the schema its
 * configuration names and {@code public} when it names none.
 *
 * <p>An unqualified and a name qualified with the modeled schema are the same
 * name, so under the modeled schema {@code billing} the names {@code users},
 * {@code billing.users}, and {@code "billing"."users"} name one table and
 * {@code mood}, {@code billing.mood}, and {@code "billing"."mood"} name one
 * type. A name qualified with any other schema, {@code public} included, names
 * an object sqlcj leaves unmodeled. A schema source's {@code search_path} is
 * never read, so it never decides which schema an unqualified name belongs to.
 *
 * <p>A schema qualifier is compared without its SQL identifier delimiters and
 * case-insensitively, as every other name sqlcj resolves is.
 */
public final class SchemaNamespace {

    /** The one schema this query group models, as the configuration names it. */
    private final String modeledSchema;

    public SchemaNamespace(String modeledSchema) {
        this.modeledSchema = modeledSchema;
    }

    /** The modeled schema, as the configuration names it. */
    public String name() {
        return modeledSchema;
    }

    /**
     * Reports whether a declared schema qualifier names the modeled schema. A
     * name without a qualifier does, and a qualified name does when its
     * qualifier is the modeled schema.
     */
    public boolean isModeled(String declaredSchema) {
        return declaredSchema == null
            || modeledSchema.equalsIgnoreCase(MultiPartName.unquote(declaredSchema));
    }

    /**
     * Reports whether sqlcj models the schema of the table one statement or
     * query names.
     */
    public boolean isModeled(Table table) {
        return isModeled(table.getSchemaName());
    }

    /**
     * The name a diagnostic states for the table one statement or query names:
     * its own name, qualified with its schema when sqlcj does not model that
     * schema, each part without its SQL identifier delimiters.
     */
    public String declaredName(Table table) {
        String declaredSchema = table.getSchemaName();

        return isModeled(declaredSchema)
            ? table.getUnquotedName()
            : MultiPartName.unquote(declaredSchema) + "." + table.getUnquotedName();
    }

    /**
     * The modeled name a declared type name states, or {@code null} when it is
     * qualified with a schema sqlcj does not model.
     *
     * <p>The parser reports a type name as one text, its qualifier and its SQL
     * identifier delimiters included, so the text is split at the dots that
     * stand outside double quotes and each part is read without its delimiters.
     */
    public String modeledTypeName(String declaredType) {
        List<String> parts = parts(declaredType);

        if (parts.size() == 1) {
            return parts.get(0);
        }

        return parts.size() == 2 && modeledSchema.equalsIgnoreCase(parts.get(0))
            ? parts.get(1)
            : null;
    }

    /**
     * The name a declared type name states whatever schema qualifies it, or
     * {@code null} when it states more parts than a schema and a name. An
     * extension's types stand in the schema the extension was installed in,
     * which is often not the modeled one, so the type of such a name is
     * resolved from the name alone, as {@link ColumnTypeMapping} resolves
     * {@code citext}.
     */
    static String typeNameInAnySchema(String declaredType) {
        List<String> parts = parts(declaredType);

        return parts.size() <= 2
            ? parts.get(parts.size() - 1)
            : null;
    }

    /**
     * The name PostgreSQL stores for one written identifier, which is the name
     * an executed statement must write to read the object again: a delimited
     * name keeps its own letters, with every doubled quote read as one quote,
     * and every other name is folded to lower case, as PostgreSQL folds an
     * undelimited identifier.
     *
     * <p>Only the ASCII letters are folded, because that is the folding
     * PostgreSQL performs on the identifiers sqlcj models.
     */
    public static String storedName(String writtenName) {
        if (
            writtenName.length() >= 2
                && writtenName.charAt(0) == '"'
                && writtenName.charAt(writtenName.length() - 1) == '"'
        ) {
            return writtenName
                .substring(1, writtenName.length() - 1)
                .replace("\"\"", "\"");
        }

        StringBuilder folded = new StringBuilder(writtenName.length());

        for (int index = 0; index < writtenName.length(); index++) {
            char character = writtenName.charAt(index);

            folded.append(
                character >= 'A' && character <= 'Z'
                    ? (char) (character - 'A' + 'a')
                    : character
            );
        }

        return folded.toString();
    }

    /**
     * The parts of one possibly qualified name text, split at the dots that
     * stand outside double quotes, each without its SQL identifier delimiters.
     */
    private static List<String> parts(String declaredName) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean delimited = false;

        for (int i = 0; i < declaredName.length(); i++) {
            char character = declaredName.charAt(i);

            if (character == '"') {
                delimited = !delimited;
            } else if (character == '.' && !delimited) {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(character);
            }
        }

        parts.add(part.toString());

        return parts;
    }
}
