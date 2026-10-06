package net.luis.artifactory;

import net.luis.artifactory.auth.AuthService;
import net.luis.artifactory.auth.UserService;
import net.luis.artifactory.config.ServerConfig;
import net.luis.artifactory.repository.*;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

public record Services(
	@NonNull ServerConfig config,
	@NonNull UserService users,
	@NonNull AuthService auth,
	@NonNull RepositoryService repositories,
	@NonNull ArtifactService artifacts,
	@NonNull PackageService packages
) {
	
	public Services {
		Objects.requireNonNull(config, "Config must not be null");
		Objects.requireNonNull(users, "User service must not be null");
		Objects.requireNonNull(auth, "Auth service must not be null");
		Objects.requireNonNull(repositories, "Repository service must not be null");
		Objects.requireNonNull(artifacts, "Artifact service must not be null");
		Objects.requireNonNull(packages, "Package service must not be null");
	}
}
