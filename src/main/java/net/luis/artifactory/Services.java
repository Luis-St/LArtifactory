package net.luis.artifactory;

import net.luis.artifactory.auth.*;
import net.luis.artifactory.config.ServerConfig;
import net.luis.artifactory.http.BodyReader;
import net.luis.artifactory.repository.*;
import net.luis.utils.io.database.SqlDatabase;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

public record Services(
	@NonNull ServerConfig config,
	@NonNull UserService users,
	@NonNull AuthService auth,
	@NonNull AccountSecurityService security,
	@NonNull TokenUsageService tokenUsage,
	@NonNull RepositoryService repositories,
	@NonNull ArtifactService artifacts,
	@NonNull PackageService packages,
	@NonNull BodyReader bodies,
	@NonNull AuditService audit,
	@NonNull WebhookService webhooks,
	@NonNull ProxyService proxy,
	@NonNull SqlDatabase database
) {
	
	public Services {
		Objects.requireNonNull(config, "Config must not be null");
		Objects.requireNonNull(users, "User service must not be null");
		Objects.requireNonNull(auth, "Auth service must not be null");
		Objects.requireNonNull(security, "Account security service must not be null");
		Objects.requireNonNull(tokenUsage, "Token usage service must not be null");
		Objects.requireNonNull(repositories, "Repository service must not be null");
		Objects.requireNonNull(artifacts, "Artifact service must not be null");
		Objects.requireNonNull(packages, "Package service must not be null");
		Objects.requireNonNull(bodies, "Body reader must not be null");
		Objects.requireNonNull(audit, "Audit service must not be null");
		Objects.requireNonNull(webhooks, "Webhook service must not be null");
		Objects.requireNonNull(proxy, "Proxy service must not be null");
		Objects.requireNonNull(database, "Database must not be null");
	}
}
