package net.luis.artifactory.repository;

import net.luis.artifactory.database.entity.*;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static net.luis.artifactory.database.Tables.*;

public class RepositoryService {
	
	private static final int MAX_CACHE_ENTRIES = 10_000;
	public static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");
	private final SqlDatabase database;
	private final ArtifactService artifactService;
	// Bounded LRU cache: repository lookups use request supplied names (including misses), an unbounded cache would
	// let an attacker exhaust memory by requesting endless distinct repository names.
	private final Map<String, RepositoryClass> kinds = new ConcurrentHashMap<>();
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
	
	@NonNull
	private static RemoteConfigEntity normalize(@NonNull RemoteConfigEntity remote) {
		String url = remote.url().endsWith("/") ? remote.url().substring(0, remote.url().length() - 1) : remote.url();
		return new RemoteConfigEntity(remote.repository(), url, remote.cacheEnabled(), remote.username(), remote.password());
	}
	
	@NonNull
	public List<RepositoryEntity> list() throws SqlException {
		return this.database.from(REPOSITORIES).select().orderBy(REPOSITORY_NAME.ascending()).fetch();
	}
	
	@Nullable
	public RepositoryEntity get(@NonNull String name) throws SqlException {
		Optional<RepositoryEntity> cached = this.cache.get(name);
		if (cached != null) {
			return cached.orElse(null);
		}
		RepositoryEntity repository = this.database.from(REPOSITORIES).select().where(Sql.equalTo(REPOSITORY_NAME, name)).fetchOneOrNull();
		this.cache.put(name, Optional.ofNullable(repository));
		return repository;
	}
	
	/**
	 * Creates a repository together with its class and, for a remote repository, its upstream configuration or, for a virtual
	 * repository, its members, all in one transaction.<br>
	 */
	@NonNull
	public RepositoryEntity create(@NonNull String name, @NonNull RepositoryType type, @NonNull RepositoryClass kind, @Nullable String description, boolean publicRead, boolean allowRedeploy, @Nullable RemoteConfigEntity remote, @NonNull List<String> members) throws SqlException {
		RepositoryEntity repository = new RepositoryEntity(name, type.id(), description, publicRead, allowRedeploy, Instant.now());
		this.database.inTransaction(transaction -> {
			transaction.from(REPOSITORIES).insert(repository).execute();
			transaction.from(REPOSITORY_KINDS).insert(new RepositoryKindEntity(name, kind.name())).execute();
			if (remote != null) {
				transaction.from(REMOTE_REPOSITORIES).insert(normalize(remote)).execute();
			}
			long position = 0;
			for (String member : members) {
				transaction.from(VIRTUAL_MEMBERS).insert(new VirtualMemberEntity(name, member, position++)).execute();
			}
			return null;
		});
		this.cache.remove(name);
		this.kinds.remove(name);
		return repository;
	}
	
	@NonNull
	public RepositoryEntity create(@NonNull String name, @NonNull RepositoryType type, @Nullable String description, boolean publicRead, boolean allowRedeploy) throws SqlException {
		return this.create(name, type, RepositoryClass.LOCAL, description, publicRead, allowRedeploy, null, List.of());
	}
	
	/**
	 * Stores the class of the repositories created before classes were stored, derived from their remote configuration and members.<br>
	 *
	 * @return The number of repositories whose class was stored
	 */
	public int backfillKinds() throws SqlException {
		Set<String> known = new HashSet<>(this.database.from(REPOSITORY_KINDS).select(KIND_REPOSITORY).fetch());
		int stored = 0;
		for (RepositoryEntity repository : this.list()) {
			if (known.contains(repository.name())) {
				continue;
			}
			RepositoryClass kind;
			if (this.database.from(REMOTE_REPOSITORIES).select().where(Sql.equalTo(REMOTE_REPOSITORY, repository.name())).exists()) {
				kind = RepositoryClass.REMOTE;
			} else if (this.database.from(VIRTUAL_MEMBERS).select().where(Sql.equalTo(VIRTUAL_REPOSITORY, repository.name())).exists()) {
				kind = RepositoryClass.VIRTUAL;
			} else {
				kind = RepositoryClass.LOCAL;
			}
			this.database.from(REPOSITORY_KINDS).insert(new RepositoryKindEntity(repository.name(), kind.name())).execute();
			stored++;
		}
		this.kinds.clear();
		return stored;
	}
	
	/**
	 * Applies an update in one transaction: the general settings, and the upstream configuration or the members if given.<br>
	 */
	public void update(@NonNull String name, @Nullable String description, @Nullable Boolean publicRead, @Nullable Boolean allowRedeploy, @Nullable RemoteConfigEntity remote, @Nullable List<String> members) throws SqlException {
		this.database.inTransaction(transaction -> {
			if (description != null || publicRead != null || allowRedeploy != null) {
				var update = transaction.from(REPOSITORIES).update();
				if (description != null) {
					update = update.set(REPOSITORY_DESCRIPTION, description);
				}
				if (publicRead != null) {
					update = update.set(REPOSITORY_PUBLIC_READ, publicRead);
				}
				if (allowRedeploy != null) {
					update = update.set(REPOSITORY_ALLOW_REDEPLOY, allowRedeploy);
				}
				update.where(Sql.equalTo(REPOSITORY_NAME, name)).execute();
			}
			if (remote != null) {
				transaction.from(REMOTE_REPOSITORIES).delete().where(Sql.equalTo(REMOTE_REPOSITORY, name)).execute();
				transaction.from(REMOTE_REPOSITORIES).insert(normalize(remote)).execute();
			}
			if (members != null) {
				transaction.from(VIRTUAL_MEMBERS).delete().where(Sql.equalTo(VIRTUAL_REPOSITORY, name)).execute();
				long position = 0;
				for (String member : members) {
					transaction.from(VIRTUAL_MEMBERS).insert(new VirtualMemberEntity(name, member, position++)).execute();
				}
			}
			return null;
		});
		this.cache.remove(name);
	}
	
	public void update(@NonNull String name, @Nullable String description, @Nullable Boolean publicRead, @Nullable Boolean allowRedeploy) throws SqlException {
		if (description == null && publicRead == null && allowRedeploy == null) {
			return;
		}
		var update = this.database.from(REPOSITORIES).update();
		if (description != null) {
			update = update.set(REPOSITORY_DESCRIPTION, description);
		}
		if (publicRead != null) {
			update = update.set(REPOSITORY_PUBLIC_READ, publicRead);
		}
		if (allowRedeploy != null) {
			update = update.set(REPOSITORY_ALLOW_REDEPLOY, allowRedeploy);
		}
		update.where(Sql.equalTo(REPOSITORY_NAME, name)).execute();
		this.cache.remove(name);
	}
	
	public void delete(@NonNull String name) throws SqlException {
		this.artifactService.transaction(work -> {
			work.removed(work.sql().from(FILES).select().where(Sql.equalTo(FILE_REPOSITORY, name)).fetch());
			work.sql().from(FILES).delete().where(Sql.equalTo(FILE_REPOSITORY, name)).execute();
			work.sql().from(PACKAGE_VERSIONS).delete().where(Sql.equalTo(VERSION_REPOSITORY, name)).execute();
			work.sql().from(DIST_TAGS).delete().where(Sql.equalTo(TAG_REPOSITORY, name)).execute();
			work.sql().from(PERMISSIONS).delete().where(Sql.equalTo(PERMISSION_REPOSITORY, name)).execute();
			work.sql().from(REMOTE_REPOSITORIES).delete().where(Sql.equalTo(REMOTE_REPOSITORY, name)).execute();
			// Remove the repository both as a virtual repository and as a member of other virtual repositories
			work.sql().from(VIRTUAL_MEMBERS).delete().where(SqlCondition.anyOf(Sql.equalTo(VIRTUAL_REPOSITORY, name), Sql.equalTo(VIRTUAL_MEMBER, name))).execute();
			work.sql().from(REPOSITORY_KINDS).delete().where(Sql.equalTo(KIND_REPOSITORY, name)).execute();
			// Webhooks scoped to the repository would otherwise apply again to a new repository of the same name
			for (String webhook : work.sql().from(WEBHOOKS).select(WEBHOOK_ID).where(Sql.equalTo(WEBHOOK_REPOSITORY, name)).fetch()) {
				work.sql().from(WEBHOOK_DELIVERIES).delete().where(Sql.equalTo(DELIVERY_WEBHOOK, webhook)).execute();
			}
			work.sql().from(WEBHOOKS).delete().where(Sql.equalTo(WEBHOOK_REPOSITORY, name)).execute();
			return work.sql().from(REPOSITORIES).delete().where(Sql.equalTo(REPOSITORY_NAME, name)).execute();
		});
		this.cache.remove(name);
		this.kinds.remove(name);
	}
	
	//region Remote (proxy) repositories
	@Nullable
	public RemoteConfigEntity remoteConfig(@NonNull String name) throws SqlException {
		return this.database.from(REMOTE_REPOSITORIES).select().where(Sql.equalTo(REMOTE_REPOSITORY, name)).fetchOneOrNull();
	}
	
	//endregion
	
	//region Virtual (group) repositories
	@NonNull
	public List<VirtualMemberEntity> virtualMembers(@NonNull String name) throws SqlException {
		return this.database.from(VIRTUAL_MEMBERS).select().where(Sql.equalTo(VIRTUAL_REPOSITORY, name)).orderBy(VIRTUAL_POSITION.ascending()).fetch();
	}
	
	//endregion
	
	/**
	 * Returns whether a repository stores its own artifacts, proxies an upstream or aggregates other repositories.<br>
	 * The class is stored when the repository is created and cached, it never changes.<br>
	 */
	@NonNull
	public RepositoryClass classOf(@NonNull String name) throws SqlException {
		RepositoryClass cached = this.kinds.get(name);
		if (cached != null) {
			return cached;
		}
		String stored = this.database.from(REPOSITORY_KINDS).select(KIND_KIND).where(Sql.equalTo(KIND_REPOSITORY, name)).fetchOneOrNull();
		RepositoryClass kind = stored == null ? RepositoryClass.LOCAL : RepositoryClass.valueOf(stored);
		if (stored != null && this.kinds.size() < MAX_CACHE_ENTRIES) {
			this.kinds.put(name, kind);
		}
		return kind;
	}
}
