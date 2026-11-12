package net.luis.artifactory.util;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class Json {
	
	public static final JsonMapper MAPPER = JsonMapper.builder().build();
	
	private Json() {}
	
	@NonNull
	public static ObjectNode object() {
		return MAPPER.createObjectNode();
	}
	
	@NonNull
	public static ArrayNode array() {
		return MAPPER.createArrayNode();
	}
	
	@NonNull
	public static JsonNode parse(@NonNull String json) {
		return MAPPER.readTree(json);
	}
	
	@NonNull
	public static JsonNode parse(byte @NonNull [] json) {
		return MAPPER.readTree(json);
	}
	
	@NonNull
	public static ObjectNode parseObject(@Nullable String json) {
		if (json == null || json.isBlank()) {
			return object();
		}
		try {
			JsonNode node = MAPPER.readTree(json);
			return node instanceof ObjectNode objectNode ? objectNode : object();
		} catch (JacksonException e) {
			return object();
		}
	}
	
	@NonNull
	public static String write(@NonNull Object value) {
		return MAPPER.writeValueAsString(value);
	}
	
	@Nullable
	public static String text(@Nullable JsonNode node, @NonNull String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || value.isMissingNode() || value.isObject() || value.isArray()) {
			return null;
		}
		return value.asString();
	}
	
	@NonNull
	public static String text(@Nullable JsonNode node, @NonNull String field, @NonNull String defaultValue) {
		String value = text(node, field);
		return value == null ? defaultValue : value;
	}
}
