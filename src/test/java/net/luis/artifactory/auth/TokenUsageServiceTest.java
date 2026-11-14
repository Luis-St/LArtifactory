package net.luis.artifactory.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenUsageServiceTest {
	
	@Test
	void emptyAllowListAllowsEveryAddress() {
		assertTrue(TokenUsageService.ipAllowed("10.0.0.1", null));
		assertTrue(TokenUsageService.ipAllowed("10.0.0.1", " "));
	}
	
	@Test
	void addressesAndRangesAreMatched() {
		assertTrue(TokenUsageService.ipAllowed("10.0.0.1", "192.168.1.1, 10.0.0.1"));
		assertTrue(TokenUsageService.ipAllowed("10.1.2.3", "10.0.0.0/8"));
		assertFalse(TokenUsageService.ipAllowed("11.0.0.1", "10.0.0.0/8"));
		assertTrue(TokenUsageService.ipAllowed("2001:db8::1", "2001:db8::/32"));
		assertFalse(TokenUsageService.ipAllowed("2001:db9::1", "2001:db8::/32"));
	}
	
	@Test
	void ipv4MappedAddressesMatchIpv4Entries() {
		assertTrue(TokenUsageService.ipAllowed("::ffff:10.0.0.1", "10.0.0.1"));
		assertTrue(TokenUsageService.ipAllowed("[::ffff:10.0.0.1]", "10.0.0.0/24"));
	}
	
	@Test
	void unparseableClientsAreDenied() {
		assertFalse(TokenUsageService.ipAllowed(null, "10.0.0.1"));
		assertFalse(TokenUsageService.ipAllowed("example.com", "10.0.0.1"));
	}
	
	@Test
	void allowListPrefixesAreLimitedByTheAddressLength() {
		assertTrue(TokenUsageService.validAllowList("10.0.0.0/32, 2001:db8::/128"));
		assertFalse(TokenUsageService.validAllowList("10.0.0.0/33"));
		assertFalse(TokenUsageService.validAllowList("2001:db8::/129"));
		assertFalse(TokenUsageService.validAllowList("10.0.0.0/-1"));
		assertFalse(TokenUsageService.validAllowList("10.0.0.0/x"));
		assertFalse(TokenUsageService.validAllowList("host.example"));
	}
}
