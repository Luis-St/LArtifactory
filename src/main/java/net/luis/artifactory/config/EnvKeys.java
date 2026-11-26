package net.luis.artifactory.config;

public final class EnvKeys {
	
	// Core
	public static final String PORT = "ARTIFACTORY_PORT";
	public static final String BASE_URL = "ARTIFACTORY_BASE_URL";
	public static final String HOST_REPOSITORIES = "ARTIFACTORY_HOST_REPOSITORIES";
	public static final String TRUST_PROXY = "ARTIFACTORY_TRUST_PROXY";
	public static final String ENABLE_API_DOCS = "ARTIFACTORY_ENABLE_API_DOCS";
	
	// Storage
	public static final String STORAGE_PATH = "ARTIFACTORY_STORAGE_PATH";
	public static final String MAX_UPLOAD_SIZE = "ARTIFACTORY_MAX_UPLOAD_SIZE_MB";
	public static final String MAX_BUFFERED_UPLOAD_SIZE = "ARTIFACTORY_MAX_BUFFERED_UPLOAD_SIZE_MB";
	public static final String MAINTENANCE_INTERVAL = "ARTIFACTORY_MAINTENANCE_INTERVAL_MINUTES";
	public static final String REMOTE_METADATA_TTL = "ARTIFACTORY_REMOTE_METADATA_TTL_MINUTES";
	public static final String AUDIT_RETENTION_DAYS = "ARTIFACTORY_AUDIT_RETENTION_DAYS";
	
	// Security
	public static final String ADMIN_USERNAME = "ARTIFACTORY_ADMIN_USERNAME";
	public static final String ADMIN_PASSWORD = "ARTIFACTORY_ADMIN_PASSWORD";
	public static final String LOGIN_MAX_FAILURES = "ARTIFACTORY_LOGIN_MAX_FAILURES";
	public static final String LOGIN_LOCKOUT_MINUTES = "ARTIFACTORY_LOGIN_LOCKOUT_MINUTES";
	public static final String SESSION_HOURS = "ARTIFACTORY_SESSION_HOURS";
	public static final String SESSION_REMEMBER_DAYS = "ARTIFACTORY_SESSION_REMEMBER_DAYS";
	public static final String NPM_TOKEN_DAYS = "ARTIFACTORY_NPM_TOKEN_DAYS";
	public static final String PASSWORD_PEPPER = "ARTIFACTORY_PASSWORD_PEPPER";
	public static final String MAIL_CODE_MINUTES = "ARTIFACTORY_MAIL_CODE_MINUTES";
	public static final String PASSWORD_RESET_MINUTES = "ARTIFACTORY_PASSWORD_RESET_MINUTES";
	public static final String DEBIAN_SIGNING_KEY = "ARTIFACTORY_DEBIAN_SIGNING_KEY";
	public static final String DEBIAN_SIGNING_PASSPHRASE = "ARTIFACTORY_DEBIAN_SIGNING_PASSPHRASE";
	public static final String DEBIAN_ARCHITECTURES = "ARTIFACTORY_DEBIAN_ARCHITECTURES";
	
	// Mail
	public static final String SMTP_HOST = "ARTIFACTORY_SMTP_HOST";
	public static final String SMTP_PORT = "ARTIFACTORY_SMTP_PORT";
	public static final String SMTP_USERNAME = "ARTIFACTORY_SMTP_USERNAME";
	public static final String SMTP_PASSWORD = "ARTIFACTORY_SMTP_PASSWORD";
	public static final String SMTP_FROM = "ARTIFACTORY_SMTP_FROM";
	public static final String SMTP_SECURITY = "ARTIFACTORY_SMTP_SECURITY";
	
	// Database
	public static final String DB_URL = "ARTIFACTORY_DB_URL";
	public static final String DB_USER = "ARTIFACTORY_DB_USERNAME";
	public static final String DB_PASSWORD = "ARTIFACTORY_DB_PASSWORD";
	public static final String DB_POOL_SIZE = "ARTIFACTORY_DB_POOL_SIZE";
	
	private EnvKeys() {}
}
