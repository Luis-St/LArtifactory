package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;

/**
 * A member of a virtual (group) repository. Members are resolved in ascending {@link #position} order.<br>
 *
 * @param repository The name of the virtual repository
 * @param member The name of a member repository
 * @param position The resolution order (lower is tried first)
 */
public record VirtualMemberEntity(
	@NonNull String repository,
	@NonNull String member,
	long position
) {}
