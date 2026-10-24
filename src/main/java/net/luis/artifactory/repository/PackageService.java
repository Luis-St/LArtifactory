package net.luis.artifactory.repository;

import net.luis.artifactory.database.Conditions;
import net.luis.artifactory.database.UnitOfWork;
import net.luis.artifactory.database.entity.DistTagEntity;
import net.luis.artifactory.database.entity.PackageVersionEntity;
import net.luis.utils.io.database.*;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;

import static net.luis.artifactory.database.Tables.*;

/**
 * Stores package versions with their ecosystem specific metadata (json) and npm style dist tags.<br>
 * Methods taking a {@link UnitOfWork} run inside the transaction of the unit, all others run on their own.<br>
 */
public class PackageService {
	
	private final SqlDatabase database;
	
	public PackageService(@NonNull SqlDatabase database) {
		this.database = Objects.requireNonNull(database, "Database must not be null");
	}
	
	private static @Nullable PackageVersionEntity get(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return sql.from(PACKAGE_VERSIONS).select().where(versionCondition(repository, packageName, version)).fetchOneOrNull();
	}
	
	private static @NonNull List<PackageVersionEntity> versions(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String packageName) throws SqlException {
		return sql.from(PACKAGE_VERSIONS).select()
			.where(SqlCondition.allOf(Sql.equalTo(VERSION_REPOSITORY, repository), Sql.equalTo(VERSION_PACKAGE, packageName)))
			.orderBy(VERSION_CREATED_AT.ascending())
			.fetch();
	}
	
	private static void updateMetadata(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String packageName, @NonNull String version, @NonNull String metadata) throws SqlException {
		sql.from(PACKAGE_VERSIONS).update().set(VERSION_METADATA, metadata).where(versionCondition(repository, packageName, version)).execute();
	}
	
	private static @NonNull SqlCondition versionCondition(@NonNull String repository, @NonNull String packageName, @NonNull String version) {
		return SqlCondition.allOf(Sql.equalTo(VERSION_REPOSITORY, repository), Sql.equalTo(VERSION_PACKAGE, packageName), Sql.equalTo(VERSION_VERSION, version));
	}
	
	private static @NonNull SqlCondition nameCondition(@NonNull String repository, @NonNull String query) {
		if (query.isEmpty()) {
			return Sql.equalTo(VERSION_REPOSITORY, repository);
		}
		return SqlCondition.allOf(Sql.equalTo(VERSION_REPOSITORY, repository), Conditions.containsIgnoreCase(VERSION_PACKAGE, query));
	}
	
	private static @NonNull Map<String, String> tags(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String packageName) throws SqlException {
		Map<String, String> tags = new LinkedHashMap<>();
		List<DistTagEntity> entities = sql.from(DIST_TAGS).select()
			.where(SqlCondition.allOf(Sql.equalTo(TAG_REPOSITORY, repository), Sql.equalTo(TAG_PACKAGE, packageName)))
			.orderBy(TAG_NAME.ascending())
			.fetch();
		for (DistTagEntity entity : entities) {
			tags.put(entity.tag(), entity.version());
		}
		return tags;
	}
	
	/**
	 * Points the tag to the version, safe against a concurrent creation of the same tag.<br>
	 */
	private static void setTag(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String packageName, @NonNull String tag, @NonNull String version) throws SqlException {
		SqlCondition condition = tagCondition(repository, packageName, tag);
		if (sql.from(DIST_TAGS).update().set(TAG_VERSION, version).where(condition).execute() > 0) {
			return;
		}
		if (sql.from(DIST_TAGS).insert(new DistTagEntity(repository, packageName, tag, version), TAG_REPOSITORY, TAG_PACKAGE, TAG_NAME).execute() == 0) {
			sql.from(DIST_TAGS).update().set(TAG_VERSION, version).where(condition).execute();
		}
	}
	
	private static boolean deleteTag(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String packageName, @NonNull String tag) throws SqlException {
		return sql.from(DIST_TAGS).delete().where(tagCondition(repository, packageName, tag)).execute() > 0;
	}
	
	private static @NonNull SqlCondition tagCondition(@NonNull String repository, @NonNull String packageName, @NonNull String tag) {
		return SqlCondition.allOf(Sql.equalTo(TAG_REPOSITORY, repository), Sql.equalTo(TAG_PACKAGE, packageName), Sql.equalTo(TAG_NAME, tag));
	}
	
	public @Nullable PackageVersionEntity get(@NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return get(this.database, repository, packageName, version);
	}
	
	public @Nullable PackageVersionEntity get(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return get(work.sql(), repository, packageName, version);
	}
	
	public @NonNull List<PackageVersionEntity> versions(@NonNull String repository, @NonNull String packageName) throws SqlException {
		return versions(this.database, repository, packageName);
	}
	
	public @NonNull List<PackageVersionEntity> versions(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName) throws SqlException {
		return versions(work.sql(), repository, packageName);
	}
	
	/**
	 * Returns the versions of several packages with one query, keyed by package name and each ordered by creation time.<br>
	 * Packages without versions are missing from the result.<br>
	 */
	public @NonNull Map<String, List<PackageVersionEntity>> versions(@NonNull String repository, @NonNull Collection<String> packageNames) throws SqlException {
		Map<String, List<PackageVersionEntity>> result = new HashMap<>();
		if (packageNames.isEmpty()) {
			return result;
		}
		List<PackageVersionEntity> versions = this.database.from(PACKAGE_VERSIONS).select()
			.where(SqlCondition.allOf(Sql.equalTo(VERSION_REPOSITORY, repository), Sql.in(VERSION_PACKAGE, packageNames.toArray(String[]::new))))
			.orderBy(VERSION_CREATED_AT.ascending())
			.fetch();
		for (PackageVersionEntity version : versions) {
			result.computeIfAbsent(version.packageName(), _ -> new ArrayList<>()).add(version);
		}
		return result;
	}
	
	/**
	 * Returns the dist tags of several packages with one query, keyed by package name.<br>
	 * Packages without tags are missing from the result.<br>
	 */
	public @NonNull Map<String, Map<String, String>> tags(@NonNull String repository, @NonNull Collection<String> packageNames) throws SqlException {
		Map<String, Map<String, String>> result = new HashMap<>();
		if (packageNames.isEmpty()) {
			return result;
		}
		List<DistTagEntity> entities = this.database.from(DIST_TAGS).select()
			.where(SqlCondition.allOf(Sql.equalTo(TAG_REPOSITORY, repository), Sql.in(TAG_PACKAGE, packageNames.toArray(String[]::new))))
			.orderBy(TAG_NAME.ascending())
			.fetch();
		for (DistTagEntity entity : entities) {
			result.computeIfAbsent(entity.packageName(), _ -> new LinkedHashMap<>()).put(entity.tag(), entity.version());
		}
		return result;
	}
	
	public @NonNull List<String> packageNames(@NonNull String repository) throws SqlException {
		return this.packageNames(repository, "", 0, Long.MAX_VALUE);
	}
	
	/**
	 * Returns one page of the package names containing the query (case-insensitive), sorted by name.<br>
	 */
	public @NonNull List<String> packageNames(@NonNull String repository, @NonNull String query, long offset, long limit) throws SqlException {
		var select = this.database.from(PACKAGE_VERSIONS).select(VERSION_PACKAGE).where(nameCondition(repository, query)).distinct().orderBy(VERSION_PACKAGE.ascending());
		if (offset > 0) {
			select = select.offset(offset);
		}
		if (limit < Long.MAX_VALUE) {
			select = select.limit(limit);
		}
		return select.fetch();
	}
	
	public long countPackages(@NonNull String repository, @NonNull String query) throws SqlException {
		return this.database.from(PACKAGE_VERSIONS).select(Sql.count(VERSION_PACKAGE, true)).where(nameCondition(repository, query)).fetchOne();
	}
	
	public @NonNull PackageVersionEntity create(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String version, @NonNull String metadata, @Nullable String createdBy) throws SqlException {
		PackageVersionEntity entity = new PackageVersionEntity(repository, packageName, version, metadata, false, Instant.now(), createdBy);
		work.sql().from(PACKAGE_VERSIONS).insert(entity).execute();
		return entity;
	}
	
	/**
	 * Creates the version if it does not exist yet, a concurrent creation of the same version is not an error.<br>
	 *
	 * @return true if the version was created
	 */
	public boolean ensure(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String version, @NonNull String metadata, @Nullable String createdBy) throws SqlException {
		PackageVersionEntity entity = new PackageVersionEntity(repository, packageName, version, metadata, false, Instant.now(), createdBy);
		return work.sql().from(PACKAGE_VERSIONS).insert(entity, VERSION_REPOSITORY, VERSION_PACKAGE, VERSION_VERSION).execute() > 0;
	}
	
	/**
	 * Creates the version if it does not exist yet, otherwise replaces its metadata.<br>
	 */
	public void upsert(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String version, @NonNull String metadata, @Nullable String createdBy) throws SqlException {
		if (!this.ensure(work, repository, packageName, version, metadata, createdBy)) {
			this.updateMetadata(work, repository, packageName, version, metadata);
		}
	}
	
	public void updateMetadata(@NonNull String repository, @NonNull String packageName, @NonNull String version, @NonNull String metadata) throws SqlException {
		updateMetadata(this.database, repository, packageName, version, metadata);
	}
	
	public void updateMetadata(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String version, @NonNull String metadata) throws SqlException {
		updateMetadata(work.sql(), repository, packageName, version, metadata);
	}
	
	public boolean setYanked(@NonNull String repository, @NonNull String packageName, @NonNull String version, boolean yanked) throws SqlException {
		return this.database.from(PACKAGE_VERSIONS).update().set(VERSION_YANKED, yanked).where(versionCondition(repository, packageName, version)).execute() > 0;
	}
	
	/**
	 * Updates the metadata and the yanked flag of a version in one statement.<br>
	 */
	public boolean setYanked(@NonNull String repository, @NonNull String packageName, @NonNull String version, boolean yanked, @NonNull String metadata) throws SqlException {
		return this.database.from(PACKAGE_VERSIONS).update()
			.set(VERSION_YANKED, yanked)
			.set(VERSION_METADATA, metadata)
			.where(versionCondition(repository, packageName, version))
			.execute() > 0;
	}
	
	public boolean delete(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return work.sql().from(PACKAGE_VERSIONS).delete().where(versionCondition(repository, packageName, version)).execute() > 0;
	}
	
	public @NonNull Map<String, String> tags(@NonNull String repository, @NonNull String packageName) throws SqlException {
		return tags(this.database, repository, packageName);
	}
	
	public @NonNull Map<String, String> tags(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName) throws SqlException {
		return tags(work.sql(), repository, packageName);
	}
	
	public void setTag(@NonNull String repository, @NonNull String packageName, @NonNull String tag, @NonNull String version) throws SqlException {
		setTag(this.database, repository, packageName, tag, version);
	}
	
	public void setTag(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String tag, @NonNull String version) throws SqlException {
		setTag(work.sql(), repository, packageName, tag, version);
	}
	
	public boolean deleteTag(@NonNull String repository, @NonNull String packageName, @NonNull String tag) throws SqlException {
		return deleteTag(this.database, repository, packageName, tag);
	}
	
	public boolean deleteTag(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String tag) throws SqlException {
		return deleteTag(work.sql(), repository, packageName, tag);
	}
	
	public void deleteTags(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName) throws SqlException {
		work.sql().from(DIST_TAGS).delete().where(SqlCondition.allOf(Sql.equalTo(TAG_REPOSITORY, repository), Sql.equalTo(TAG_PACKAGE, packageName))).execute();
	}
}
