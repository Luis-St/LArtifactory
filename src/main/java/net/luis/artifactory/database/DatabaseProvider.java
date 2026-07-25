package net.luis.artifactory.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.luis.artifactory.config.DatabaseConfig;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.audit.SqlAuditUserProvider;
import net.luis.utils.io.database.dialect.SqlDialects;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class DatabaseProvider {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseProvider.class);
	
	private final SqlDatabase database;
	
	public DatabaseProvider(@NonNull DatabaseConfig databaseConfig, @NonNull SqlAuditUserProvider auditUserProvider) {
		Objects.requireNonNull(databaseConfig, "Database config must not be null");
		Objects.requireNonNull(auditUserProvider, "Audit user provider must not be null");
		
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(databaseConfig.url());
		config.setUsername(databaseConfig.user());
		config.setPassword(databaseConfig.password());
		config.setMaximumPoolSize(databaseConfig.poolSize());
		config.setPoolName("artifactory-pool");
		
		try {
			this.database = SqlDatabase.builder(new HikariDataSource(config), SqlDialects.POSTGRESQL)
				.autoCloseDataSource(true)
				.auditUserProvider(auditUserProvider)
				.build();
		} catch (Exception e) {
			LOGGER.error("Failed to initialize database connection pool (pool={}, url={})", databaseConfig.poolSize(), databaseConfig.safeUrl(), e);
			throw new RuntimeException("Failed to initialize database connection pool", e);
		}
		
		LOGGER.info("Database connection pool initialized (pool={}, url={})", databaseConfig.poolSize(), databaseConfig.safeUrl());
	}
	
	public @NonNull SqlDatabase getDatabase() {
		return this.database;
	}
	
	public boolean isHealthy() {
		try {
			return this.isHealthy();
		} catch (Exception e) {
			LOGGER.warn("Database health check failed", e);
			return false;
		}
	}
	
	public void close() throws SqlException {
		this.database.close();
	}
}
