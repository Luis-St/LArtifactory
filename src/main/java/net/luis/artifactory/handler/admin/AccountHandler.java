package net.luis.artifactory.handler.admin;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.*;
import net.luis.artifactory.database.entity.UserSecurityEntity;
import net.luis.artifactory.dto.request.*;
import net.luis.artifactory.dto.response.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Self service of the account security: email address, two-factor authentication and password recovery.<br>
 * Changes require a password session (no token) and, where they weaken or redirect the account protection, the current password
 * and, with two-factor authentication, a second factor.<br>
 */
public class AccountHandler {
	
	private final Services services;
	
	public AccountHandler(@NonNull Services services) {
		this.services = Objects.requireNonNull(services, "Services must not be null");
	}
	
	@NonNull
	private String address(@NonNull Context ctx) {
		return Requests.clientIp(ctx, this.services.config());
	}
	
	/**
	 * Checks the current password and, if the user has two-factor authentication, the code of a second factor.<br>
	 * Failed attempts count towards the login throttle.<br>
	 */
	@NonNull
	private Principal confirm(@NonNull Context ctx, String password, String code) throws Exception {
		Principal principal = this.services.auth().requirePassword(ctx, "Changing the account security");
		if (password == null || this.services.auth().login(ctx, principal.username(), password) == null) {
			throw HttpError.forbidden("The current password is required and must be correct");
		}
		this.services.security().requireSecondFactor(principal.username(), this.address(ctx), code);
		return principal;
	}
	
	@NonNull
	private Principal confirm(@NonNull Context ctx) throws Exception {
		PasswordConfirmRequest request = ctx.bodyAsClass(PasswordConfirmRequest.class);
		return this.confirm(ctx, request.password(), request.code());
	}
	
	@OpenApi(
		summary = "Get the email address and two-factor settings of the caller",
		operationId = "getAccountSecurity",
		path = "/api/account/security",
		methods = HttpMethod.GET,
		tags = "Account",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AccountSecurityResponse.class))
	)
	public void get(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requirePassword(ctx, "Changing the account security");
		this.respond(ctx, principal.username());
	}
	
	private void respond(@NonNull Context ctx, @NonNull String username) throws Exception {
		UserSecurityEntity security = this.services.users().security(username);
		ctx.json(new AccountSecurityResponse(security.email(), security.totpEnabled(), security.emailMfaEnabled(), RecoveryCodes.remaining(security.recoveryCodes()), this.services.security().mailAvailable()));
	}
	
	@OpenApi(
		summary = "Send a verification code to a new email address, requires the current password and, with two-factor authentication, a second factor",
		operationId = "requestEmailChange",
		path = "/api/account/email",
		methods = HttpMethod.POST,
		tags = "Account",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = EmailChangeRequest.class)),
		responses = {
			@OpenApiResponse(status = "202"),
			@OpenApiResponse(status = "409", description = "The address is used by another account", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "503", description = "Mail is not configured", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void requestEmail(@NonNull Context ctx) throws Exception {
		EmailChangeRequest request = ctx.bodyAsClass(EmailChangeRequest.class);
		Principal principal = this.confirm(ctx, request.password(), request.code());
		this.services.security().requestEmailChange(principal.username(), request.email());
		ctx.status(202);
	}
	
	@OpenApi(
		summary = "Verify the new email address with the code sent to it",
		operationId = "verifyEmail",
		path = "/api/account/email/verify",
		methods = HttpMethod.POST,
		tags = "Account",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CodeRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AccountSecurityResponse.class))
	)
	public void verifyEmail(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requirePassword(ctx, "Changing the account security");
		this.services.security().confirmEmailChange(principal.username(), this.address(ctx), ctx.bodyAsClass(CodeRequest.class).code());
		this.respond(ctx, principal.username());
	}
	
	@OpenApi(
		summary = "Remove the email address, requires the current password and, with two-factor authentication, a second factor",
		operationId = "removeEmail",
		path = "/api/account/email/remove",
		methods = HttpMethod.POST,
		tags = "Account",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PasswordConfirmRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AccountSecurityResponse.class))
	)
	public void removeEmail(@NonNull Context ctx) throws Exception {
		Principal principal = this.confirm(ctx);
		this.services.security().removeEmail(principal.username(), this.address(ctx));
		this.respond(ctx, principal.username());
	}
	
	@OpenApi(
		summary = "Send a code to the verified email address that confirms one change of the account security",
		description = "Only for users with codes by email, the code is accepted as the second factor of the other account endpoints",
		operationId = "sendConfirmationCode",
		path = "/api/account/mfa/email/code",
		methods = HttpMethod.POST,
		tags = "Account",
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "429", description = "A code was sent moments ago"),
			@OpenApiResponse(status = "503", description = "Mail is not configured", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void sendConfirmationCode(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requirePassword(ctx, "Changing the account security");
		this.services.security().sendStepUpCode(principal.username());
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "Start setting up an authenticator app, requires the current password and, with two-factor authentication, a second factor",
		description = "Returns the secret and the otpauth uri for the qr code, POST /api/account/mfa/totp/enable confirms the setup with a code of the app",
		operationId = "setupTotp",
		path = "/api/account/mfa/totp/setup",
		methods = HttpMethod.POST,
		tags = "Account",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PasswordConfirmRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = TotpSetupResponse.class))
	)
	public void setupTotp(@NonNull Context ctx) throws Exception {
		Principal principal = this.confirm(ctx);
		AccountSecurityService.TotpSetup setup = this.services.security().beginTotp(principal.username());
		ctx.json(new TotpSetupResponse(setup.secret(), setup.uri()));
	}
	
	@OpenApi(
		summary = "Enable the authenticator app with a code of it, returns recovery codes if the account had none",
		operationId = "enableTotp",
		path = "/api/account/mfa/totp/enable",
		methods = HttpMethod.POST,
		tags = "Account",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CodeRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = RecoveryCodesResponse.class))
	)
	public void enableTotp(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requirePassword(ctx, "Changing the account security");
		ctx.json(new RecoveryCodesResponse(this.services.security().enableTotp(principal.username(), this.address(ctx), ctx.bodyAsClass(CodeRequest.class).code())));
	}
	
	@OpenApi(
		summary = "Enable sign in codes by email, requires a verified email address, the current password and, with two-factor authentication, a second factor",
		operationId = "enableEmailMfa",
		path = "/api/account/mfa/email/enable",
		methods = HttpMethod.POST,
		tags = "Account",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PasswordConfirmRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = RecoveryCodesResponse.class))
	)
	public void enableEmail(@NonNull Context ctx) throws Exception {
		Principal principal = this.confirm(ctx);
		ctx.json(new RecoveryCodesResponse(this.services.security().enableEmailMfa(principal.username(), this.address(ctx))));
	}
	
	@OpenApi(
		summary = "Disable a second factor (totp or email), requires the current password and, with two-factor authentication, a second factor",
		operationId = "disableMfa",
		path = "/api/account/mfa/{method}/disable",
		methods = HttpMethod.POST,
		tags = "Account",
		pathParams = @OpenApiParam(name = "method", required = true, description = "totp or email"),
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PasswordConfirmRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AccountSecurityResponse.class))
	)
	public void disable(@NonNull Context ctx) throws Exception {
		AccountSecurityService.MfaMethod method = AccountSecurityService.MfaMethod.parse(ctx.pathParam("method"));
		if (method == null || method == AccountSecurityService.MfaMethod.RECOVERY) {
			throw HttpError.notFound("No such second factor, use totp or email");
		}
		Principal principal = this.confirm(ctx);
		this.services.security().disable(principal.username(), this.address(ctx), method);
		this.respond(ctx, principal.username());
	}
	
	@OpenApi(
		summary = "Replace the recovery codes with " + RecoveryCodes.COUNT + " new ones, requires the current password and, with two-factor authentication, a second factor",
		operationId = "regenerateRecoveryCodes",
		path = "/api/account/mfa/recovery-codes",
		methods = HttpMethod.POST,
		tags = "Account",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PasswordConfirmRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = RecoveryCodesResponse.class))
	)
	public void regenerateRecoveryCodes(@NonNull Context ctx) throws Exception {
		Principal principal = this.confirm(ctx);
		ctx.json(new RecoveryCodesResponse(this.services.security().regenerateRecoveryCodes(principal.username(), this.address(ctx))));
	}
	
	@OpenApi(
		summary = "Whether password recovery by email is available",
		operationId = "passwordResetAvailable",
		path = "/api/password-reset",
		methods = HttpMethod.GET,
		tags = "Account",
		security = {},
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PasswordResetResponse.class))
	)
	public void resetAvailable(@NonNull Context ctx) {
		ctx.json(new PasswordResetResponse(this.services.security().mailAvailable(), null, false));
	}
	
	@OpenApi(
		summary = "Request a password reset mail for a user name or email address",
		description = "Always answers 202, so the response does not tell whether the account exists or has an email address",
		operationId = "requestPasswordReset",
		path = "/api/password-reset",
		methods = HttpMethod.POST,
		tags = "Account",
		security = {},
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PasswordResetRequest.class)),
		responses = @OpenApiResponse(status = "202")
	)
	public void requestReset(@NonNull Context ctx) throws Exception {
		this.services.security().requestPasswordReset(this.address(ctx), ctx.bodyAsClass(PasswordResetRequest.class).login());
		ctx.status(202);
	}
	
	@OpenApi(
		summary = "Check a password reset token, tells whether completing it needs a code of the authenticator app",
		operationId = "checkPasswordReset",
		path = "/api/password-reset/check",
		methods = HttpMethod.POST,
		tags = "Account",
		security = {},
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PasswordResetCompleteRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PasswordResetResponse.class))
	)
	public void checkReset(@NonNull Context ctx) throws Exception {
		AccountSecurityService.ResetInfo info = this.services.security().checkPasswordReset(ctx.bodyAsClass(PasswordResetCompleteRequest.class).token());
		ctx.json(new PasswordResetResponse(true, info.username(), info.totpRequired()));
	}
	
	@OpenApi(
		summary = "Set a new password with a reset token, ends all sessions of the user and revokes its access tokens if asked to",
		operationId = "completePasswordReset",
		path = "/api/password-reset/complete",
		methods = HttpMethod.POST,
		tags = "Account",
		security = {},
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PasswordResetCompleteRequest.class)),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "400", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void completeReset(@NonNull Context ctx) throws Exception {
		PasswordResetCompleteRequest request = ctx.bodyAsClass(PasswordResetCompleteRequest.class);
		UserAdminHandler.validatePassword(request.password());
		this.services.security().completePasswordReset(this.address(ctx), request.token(), Objects.requireNonNull(request.password()), request.code(), Boolean.TRUE.equals(request.revokeTokens()));
		ctx.status(204);
	}
}
