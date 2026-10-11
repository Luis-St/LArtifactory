package net.luis.artifactory.repository;

import net.luis.artifactory.database.entity.FileEntity;
import net.luis.artifactory.database.entity.RepositoryEntity;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static net.luis.artifactory.database.Tables.*;

public class RepositoryService {

	public static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");
	private static final int MAX_CACHE_ENTRIES = 10_000;

	private final SqlDatabase database;
	private final ArtifactService artifactService;
	// Bounded LRU cache: repository lookups use request supplied names (including misses), an unbounded cache would
	// let an attacker exhaust memory by requesting endless distinct repository names.
	private final Map<String, Optional<RepositoryEntity>> cache = Collections.synchronizedMap(new LinkedHashMap<String, Optional<RepositoryEntity>>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Optional<RepositoryEntity>> eldest) {
			return this.size() > MAX_CACHE_ENTRIES;
		}
	});
	
	public RepositoryService(@NonNull SqlDatabase database, @NonNull ArtifactService artifactService) {
		this.database = Objects.requireNonNull(database, "Database must not be null");
		this.artifactService = Objects.requireNonNull(artifactService, "Artifact service must not be null");
	}
	
	public @NonNull List<RepositoryEntity> list() throws SqlException {
		return this.database.from(REPOSITORIES).select().orderBy(REPOSITORY_NAME.ascending()).fetch();
	}
	
	public @Nullable RepositoryEntity get(@NonNull String name) throws SqlException {
		Optional<RepositoryEntity> cached = this.cache.get(name);
		if (cached != null) {
			return cached.orElse(null);
		}
		RepositoryEntity repository = this.database.from(REPOSITORIES).select().where(Sql.equalTo(REPOSITORY_NAME, name)).fetchOneOrNull();
		this.cache.put(name, Optional.ofNullable(repository));
		return repository;
	}
	
	public @NonNull RepositoryEntity create(@NonNull String name, @NonNull RepositoryType type, @Nullable String description, boolean publicRead, boolean allowRedeploy) throws SqlException {
		RepositoryEntity repository = new RepositoryEntity(name, type.id(), description, publicRead, allowRedeploy, Instant.now());
		this.database.from(REPOSITORIES).insert(repository).execute();
		this.cache.remove(name);
		return repository;
	}
	
	public void update(@NonNull String name, @Nullable String description, @Nullable Boolean publicRead, @Nullable Boolean allowRedeploy) throws SqlException {
		if (description != null) {
			this.database.from(REPOSITORIES).update().set(REPOSITORY_DESCRIPTION, description).where(Sql.equalTo(REPOSITORY_NAME, name)).execute();
		}
		if (publicRead != null) {
			this.database.from(REPOSITORIES).update().set(REPOSITORY_PUBLIC_READ, publicRead).where(Sql.equalTo(REPOSITORY_NAME, name)).execute();
		}
		if (allowRedeploy != null) {
			this.database.from(REPOSITORIES).update().set(REPOSITORY_ALLOW_REDEPLOY, allowRedeploy).where(Sql.equalTo(REPOSITORY_NAME, name)).execute();
		}
		this.cache.remove(name);
	}
	
	public void delete(@NonNull String name) throws SqlException {
		List<FileEntity> files = this.database.from(FILES).select().where(Sql.equalTo(FILE_REPOSITORY, name)).fetch();
		this.database.inTransaction(transaction -> {
			transaction.from(FILES).delete().where(Sql.equalTo(FILE_REPOSITORY, name)).execute();
			transaction.from(PACKAGE_VERSIONS).delete().where(Sql.equalTo(VERSION_REPOSITORY, name)).execute();
			transaction.from(DIST_TAGS).delete().where(Sql.equalTo(TAG_REPOSITORY, name)).execute();
			transaction.from(PERMISSIONS).delete().where(Sql.equalTo(PERMISSION_REPOSITORY, name)).execute();
			return transaction.from(REPOSITORIES).delete().where(Sql.equalTo(REPOSITORY_NAME, name)).execute();
		});
		this.cache.remove(name);
		this.artifactService.releaseBlobs(files);
	}
}
