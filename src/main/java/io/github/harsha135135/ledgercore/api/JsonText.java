package io.github.harsha135135.ledgercore.api;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes response and audit bodies once, so a stored idempotent response can be replayed
 * byte for byte.
 */
@Component
public class JsonText {

	private final JsonMapper mapper;

	public JsonText(JsonMapper mapper) {
		this.mapper = mapper;
	}

	public String write(Object value) {
		return mapper.writeValueAsString(value);
	}

}
