package org.sqlcj.analysis;

/** One column reference resolved against the ordered query sources. */
record ResolvedColumn(Source source, org.sqlcj.schema.Column column) {
}
