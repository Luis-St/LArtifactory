package net.luis.artifactory.repository;

import net.luis.artifactory.database.entity.DistTagEntity;
import net.luis.artifactory.database.entity.PackageVersionEntity;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;

import static net.luis.artifactory.database.Tables.*;

/**
 * Stores package versions with their ecosystem specific metadata (json) and npm style dist tags.<br>
 */
public class PackageService {
	
	private final SqlDatabase database;
	
	public PackageService(@NonNull SqlDatabase database) {
		this.database = Objects.requireNonNull(database, "Database must not be null");
	}
	
	public @Nullable PackageVersionEntity get(@NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return this.database.from(PACKAGE_VERSIONS).select().where(SqlCondition.allOf(
			Sql.equalTo(VERSION_REPOSITORY, repository), Sql.equalTo(VERSION_PACKAGE, packageName), Sql.equalTo(VERSION_VERSION, version)
		)).fetchOneOrNull();
	}
	
	public @NonNull List<PackageVersionEntity> versions(@NonNull String repository, @NonNull String packageName) throws SqlException {
		return this.database.from(PACKAGE_VERSIONS).select()
			.where(SqlCondition.allOf(Sql.equalTo(VERSION_REPOSITORY, repository), Sql.equalTo(VERSION_PACKAGE, packageName)))
			.orderBy(VERSION_CREATED_AT.ascending())
			.fetch();
	}
	
	public @NonNull List<PackageVersionEntity> all(@NonNull String repository) throws SqlException {
		return this.database.from(PACKAGE_VERSIONS).select()
			.where(Sql.equalTo(VERSION_REPOSITORY, repository))
			.orderBy(VERSION_PACKAGE.ascending(), VERSION_CREATED_AT.ascending())
			.fetch();
	}
	
	/**
	 * Returns all versions grouped by package name, sorted by package name.<br>
	 */
	public @NonNull Map<String, List<PackageVersionEntity>> grouped(@NonNull String repository) throws SqlException {
		Map<String, List<PackageVersionEntity>> grouped = new TreeMap<>();
		for (PackageVersionEntity version : this.all(repository)) {
			grouped.computeIfAbsent(version.packageName(), _ -> new ArrayList<>()).add(version);
		}
		return grouped;
	}
	
	public @NonNull List<String> packageNames(@NonNull String repository) throws SqlException {
		return this.database.from(PACKAGE_VERSIONS).select(VERSION_PACKAGE)
			.where(Sql.equalTo(VERSION_REPOSITORY, repository))
			.distinct()
			.orderBy(VERSION_PACKAGE.ascending())
			.fetch();
	}
	
	public @NonNull PackageVersionEntity create(@NonNull String repository, @NonNull String packageName, @NonNull String version, @NonNull String metadata, @Nullable String createdBy) throws SqlException {
		PackageVersionEntity entity = new PackageVersionEntity(repository, packageName, version, metadata, false, Instant.now(), createdBy);
		this.database.from(PACKAGE_VERSIONS).insert(entity).execute();
		return entity;
	}
	
	/**
	 * Creates the version if it does not exist yet, otherwise replaces its metadata.<br>
	 */
	public void upsert(@NonNull String repository, @NonNull String packageName, @NonNull String version, @NonNull String metadata, @Nullable String createdBy) throws SqlException {
		if (this.get(repository, packageName, version) == null) {
			this.create(repository, packageName, version, metadata, createdBy);
		} else {
			this.updateMetadata(repository, packageName, version, metadata);
		}
	}
	
	public void updateMetadata(@NonNull String repository, @NonNull String packageName, @NonNull String version, @NonNull String metadata) throws SqlException {
		this.database.from(PACKAGE_VERSIONS).update().set(VERSION_METADATA, metadata).where(this.versionCondition(repository, packageName, version)).execute();
	}
	
	public boolean setYanked(@NonNull String repository, @NonNull String packageName, @NonNull String version, boolean yanked) throws SqlException {
		return this.database.from(PACKAGE_VERSIONS).update().set(VERSION_YANKED, yanked).where(this.versionCondition(repository, packageName, version)).execute() > 0;
	}
	
	public boolean delete(@NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return this.database.from(PACKAGE_VERSIONS).delete().where(this.versionCondition(repository, packageName, version)).execute() > 0;
	}
	
	private @NonNull SqlCondition versionCondition(@NonNull String repository, @NonNull String packageName, @NonNull String version) {
		return SqlCondition.allOf(Sql.equalTo(VERSION_REPOSITORY, repository), Sql.equalTo(VERSION_PACKAGE, packageName), Sql.equalTo(VERSION_VERSION, version));
	}
	
	//region Dist tags
	public @NonNull Map<String, String> tags(@NonNull String repository, @NonNull String packageName) throws SqlException {
		Map<String, String> tags = new LinkedHashMap<>();
		List<DistTagEntity> entities = this.database.from(DIST_TAGS).select()
			.where(SqlCondition.allOf(Sql.equalTo(TAG_REPOSITORY, repository), Sql.equalTo(TAG_PACKAGE, packageName)))
			.orderBy(TAG_NAME.ascending())
			.fetch();
		for (DistTagEntity entity : entities) {
			tags.put(entity.tag(), entity.version());
		}
		return tags;
	}
	
	public void setTag(@NonNull String repository, @NonNull String packageName, @NonNull String tag, @NonNull String version) throws SqlException {
		this.database.inTransaction(transaction -> {
			transaction.from(DIST_TAGS).delete().where(this.tagCondition(repository, packageName, tag)).execute();
			return transaction.from(DIST_TAGS).insert(new DistTagEntity(repository, packageName, tag, version)).execute();
		});
	}
	
	public boolean deleteTag(@NonNull String repository, @NonNull String packageName, @NonNull String tag) throws SqlException {
		return this.database.from(DIST_TAGS).delete().where(this.tagCondition(repository, packageName, tag)).execute() > 0;
	}
	
	public void deleteTags(@NonNull String repository, @NonNull String packageName) throws SqlException {
		this.database.from(DIST_TAGS).delete().where(SqlCondition.allOf(Sql.equalTo(TAG_REPOSITORY, repository), Sql.equalTo(TAG_PACKAGE, packageName))).execute();
	}
	
	private @NonNull SqlCondition tagCondition(@NonNull String repository, @NonNull String packageName, @NonNull String tag) {
		return SqlCondition.allOf(Sql.equalTo(TAG_REPOSITORY, repository), Sql.equalTo(TAG_PACKAGE, packageName), Sql.equalTo(TAG_NAME, tag));
	}
	//endregion
}
