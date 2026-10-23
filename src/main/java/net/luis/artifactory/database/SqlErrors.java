package net.luis.artifactory.database;

import net.luis.utils.io.database.exception.database.constraint.SqlUniqueConstraintException;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;

public final class SqlErrors {
	
	private static final String UNIQUE_VIOLATION = "23505";
	
	private SqlErrors() {}
	
	/**
	 * Checks whether the exception was caused by a unique constraint violation, e.g. because a concurrent request created the same row first.<br>
	 * Not every query wraps the driver error into a {@link SqlUniqueConstraintException}, so the sql state of the causes is checked as well.<br>
	 */
	public static boolean isUniqueViolation(@Nullable Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SqlUniqueConstraintException || (current instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState()))) {
				return true;
			}
		}
		return false;
	}
}
