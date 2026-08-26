package cn.photolib.common.util;

/**
 * Shared handling for "contains" filters built on SQL {@code LIKE}.
 *
 * <p>Binding a parameter stops SQL injection but not wildcard injection: {@code %}
 * and {@code _} keep their pattern meaning inside the bound value, so a user typing
 * an underscore — a legal character in student ids, usernames and file names —
 * silently matches far more rows than they asked for.
 *
 * <p>Escaping needs an escape character to be declared, and the default differs by
 * database and by {@code sql_mode} (MySQL's {@code NO_BACKSLASH_ESCAPES} disables the
 * usual backslash). Every caller therefore pairs {@link #escape} with an explicit
 * {@code ESCAPE '!'} in the statement; {@link #contains} writes that fragment so the
 * two halves cannot drift apart.
 */
public final class LikeFilter {
    private LikeFilter() {
    }

    /** Escapes LIKE wildcards. The result is only correct alongside {@code ESCAPE '!'}. */
    public static String escape(String value) {
        return value == null ? null
                : value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * A case-sensitive "contains" fragment for MyBatis-Plus {@code apply(...)},
     * with the matching {@code ESCAPE} declaration. {@code {0}} takes the value
     * already passed through {@link #escape}.
     */
    public static String contains(String column) {
        return column + " LIKE CONCAT('%', {0}, '%') ESCAPE '!'";
    }
}
