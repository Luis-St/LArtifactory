package net.luis.artifactory.dto.response;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * @param recoveryCodes New recovery codes, only returned once. Null if the user already had codes, they stay valid.
 */
public record RecoveryCodesResponse(
	@Nullable List<String> recoveryCodes
) {}
