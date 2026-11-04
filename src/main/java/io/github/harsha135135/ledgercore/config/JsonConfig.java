package io.github.harsha135135.ledgercore.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Strict request parsing. Money travels as decimal strings, so a JSON number must not be
 * silently turned into a string, and a duplicated or misspelled field must not be ignored.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfig {

	@Bean
	JsonMapperBuilderCustomizer strictRequestParsing() {
		return builder -> builder
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.withCoercionConfig(LogicalType.Textual, config -> config
						.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
	}

}
