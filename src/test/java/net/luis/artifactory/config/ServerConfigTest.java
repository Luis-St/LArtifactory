package net.luis.artifactory.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
	
	@Test
	void usesDefaults() {
		ServerConfig config = ServerConfig.from(Env.of(Map.of()));
		assertEquals(8080, config.port());
		assertEquals(1024L * 1024 * 1024, config.maxUploadSize());
		assertEquals(256L * 1024 * 1024, config.maxBufferedUploadSize());
		assertEquals(10, config.loginMaxFailures());
		assertEquals(15, config.loginLockoutMinutes());
		assertEquals(12, config.sessionHours());
		assertEquals(30, config.sessionRememberDays());
		assertEquals(90, config.npmTokenDays());
		assertEquals(60, config.maintenanceIntervalMinutes());
		assertFalse(config.trustProxy());
	}
	
	@Test
	void parsesHostRepositoriesAndBaseUrl() {
		ServerConfig config = ServerConfig.from(Env.of(Map.of(
			EnvKeys.HOST_REPOSITORIES, "NPM.example.com=npm-local, pypi.example.com=pypi-local",
			EnvKeys.BASE_URL, "https://repo.example.com/"
		)));
		assertEquals(Map.of("npm.example.com", "npm-local", "pypi.example.com", "pypi-local"), config.hostRepositories());
		assertEquals("https://repo.example.com", config.baseUrl());
	}
	
	@Test
	void allowsNpmTokensWithoutExpiry() {
		assertEquals(0, ServerConfig.from(Env.of(Map.of(EnvKeys.NPM_TOKEN_DAYS, "0"))).npmTokenDays());
	}
	
	@Test
	void parsesDebianArchitectures() {
		assertEquals(java.util.List.of("amd64", "arm64", "armhf", "i386", "ppc64el", "riscv64", "s390x"), ServerConfig.from(Env.of(Map.of())).debianArchitectures());
		assertEquals(java.util.List.of("amd64", "arm64"), ServerConfig.from(Env.of(Map.of(EnvKeys.DEBIAN_ARCHITECTURES, "amd64, arm64 amd64"))).debianArchitectures());
		assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(Map.of(EnvKeys.DEBIAN_ARCHITECTURES, "amd64,all"))));
		assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(Map.of(EnvKeys.DEBIAN_ARCHITECTURES, "AMD64"))));
		assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(Map.of(EnvKeys.DEBIAN_ARCHITECTURES, " , "))));
	}
	
	@Test
	void rejectsInvalidValues() {
		assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(Map.of(EnvKeys.LOGIN_MAX_FAILURES, "0"))));
		assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(Map.of(EnvKeys.SESSION_HOURS, "0"))));
		assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(Map.of(EnvKeys.NPM_TOKEN_DAYS, "-1"))));
		assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(Map.of(EnvKeys.TRUST_PROXY, "maybe"))));
		assertThrows(ConfigException.class, () -> ServerConfig.from(Env.of(Map.of(EnvKeys.HOST_REPOSITORIES, "npm.example.com"))));
	}
}
