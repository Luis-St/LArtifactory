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
	
	// Security
	public static final String ADMIN_USERNAME = "ARTIFACTORY_ADMIN_USERNAME";
	public static final String ADMIN_PASSWORD = "ARTIFACTORY_ADMIN_PASSWORD";
	public static final String LOGIN_MAX_FAILURES = "ARTIFACTORY_LOGIN_MAX_FAILURES";
	public static final String LOGIN_LOCKOUT_MINUTES = "ARTIFACTORY_LOGIN_LOCKOUT_MINUTES";
	public static final String SESSION_HOURS = "ARTIFACTORY_SESSION_HOURS";
	public static final String SESSION_REMEMBER_DAYS = "ARTIFACTORY_SESSION_REMEMBER_DAYS";
	public static final String NPM_TOKEN_DAYS = "ARTIFACTORY_NPM_TOKEN_DAYS";
	
	// Database
	public static final String DB_URL = "ARTIFACTORY_DB_URL";
	public static final String DB_USER = "ARTIFACTORY_DB_USERNAME";
	public static final String DB_PASSWORD = "ARTIFACTORY_DB_PASSWORD";
	public static final String DB_POOL_SIZE = "ARTIFACTORY_DB_POOL_SIZE";
	
	private EnvKeys() {}
}
