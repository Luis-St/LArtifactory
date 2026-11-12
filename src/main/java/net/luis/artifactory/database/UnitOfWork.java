package net.luis.artifactory.database;

import net.luis.artifactory.database.entity.FileEntity;
import net.luis.utils.io.database.SqlProvider;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * The database operations of one transaction together with the files whose blobs may be released and the actions that run after the commit.<br>
 * Blobs must not be deleted inside the transaction, a rollback would leave rows pointing to deleted content.<br>
 */
public final class UnitOfWork {
	
	private final SqlProvider sql;
	private final List<FileEntity> removedFiles = new ArrayList<>();
	private final List<Runnable> afterCommitActions = new ArrayList<>();
	
	public UnitOfWork(@NonNull SqlProvider sql) {
		this.sql = Objects.requireNonNull(sql, "Sql provider must not be null");
	}
	
	public @NonNull SqlProvider sql() {
		return this.sql;
	}
	
	/**
	 * Marks the files as removed, their blobs are released once the transaction was committed.<br>
	 */
	public void removed(@NonNull Collection<FileEntity> files) {
		this.removedFiles.addAll(files);
	}
	
	public @NonNull List<FileEntity> removedFiles() {
		return Collections.unmodifiableList(this.removedFiles);
	}
	
	/**
	 * Registers an action that runs once the transaction was committed, it is dropped if the transaction is rolled back.<br>
	 */
	public void afterCommit(@NonNull Runnable action) {
		this.afterCommitActions.add(Objects.requireNonNull(action, "Action must not be null"));
	}
	
	public @NonNull List<Runnable> afterCommitActions() {
		return Collections.unmodifiableList(this.afterCommitActions);
	}
}
