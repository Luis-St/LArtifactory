package net.luis.artifactory.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConditionsTest {
	
	@Test
	void escapesLikeWildcards() {
		assertEquals("my\\_lib", Conditions.escapeLike("my_lib"));
		assertEquals("100\\%", Conditions.escapeLike("100%"));
		assertEquals("a\\\\b", Conditions.escapeLike("a\\b"));
		assertEquals("plain/path", Conditions.escapeLike("plain/path"));
	}
}
