package net.luis.artifactory.handler.format;

import net.luis.artifactory.Services;
import net.luis.artifactory.repository.RepositoryType;
import org.jspecify.annotations.NonNull;

/**
 * Generic repositories store arbitrary files under arbitrary paths.<br>
 */
public class GenericHandler extends PathRepositoryHandler {
	
	public GenericHandler(@NonNull Services services) {
		super(services);
	}
	
	@Override
	public @NonNull RepositoryType type() {
		return RepositoryType.GENERIC;
	}
}
