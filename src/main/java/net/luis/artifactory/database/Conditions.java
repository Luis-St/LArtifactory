package net.luis.artifactory.database;

import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.expression.SqlExpression;
import org.jspecify.annotations.NonNull;

import java.util.Locale;

/**
 * Conditions on user supplied text which must match literally.<br>
 * The {@code LIKE} based conditions of the query builder treat {@code %} and {@code _} as wildcards, they are escaped with
 * PostgreSQL's default escape character so a prefix like {@code my_lib} does not also match {@code myXlib}.<br>
 */
public final class Conditions {
	
	private Conditions() {}
	
	@NonNull
	public static SqlCondition startsWith(@NonNull SqlExpression<String> column, @NonNull String prefix) {
		return Sql.startsWith(column, escapeLike(prefix));
	}
	
	@NonNull
	public static SqlCondition containsIgnoreCase(@NonNull SqlExpression<String> column, @NonNull String text) {
		return Sql.contains(Sql.lower(column), escapeLike(text.toLowerCase(Locale.ROOT)));
	}
	
	@NonNull
	public static String escapeLike(@NonNull String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
