package io.github.harsha135135.ledgercore.security;

import java.util.ArrayList;
import java.util.List;

import io.github.harsha135135.ledgercore.api.ErrorResponseWriter;
import io.github.harsha135135.ledgercore.config.LedgerProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless HTTP Basic for a CLI or API client on loopback. No sessions, no remember-me and no
 * browser flow, so CSRF protection (which defends cookie-based sessions) is not used.
 * Spring Security only authenticates; ledger roles are read from PostgreSQL by the services.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	static final String LEDGER_USER = "LEDGER_USER";
	/** The outbox publisher's service credential: may call the consumer, nothing else. */
	static final String EVENT_PUBLISHER = "EVENT_PUBLISHER";
	/** The diagnostics scrape credential: may read /actuator/prometheus, nothing else. */
	static final String DIAGNOSTICS_READER = "DIAGNOSTICS_READER";

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, ErrorResponseWriter errors) throws Exception {
		AuthenticationEntryPoint unauthenticated = (request, response, ex) -> {
			response.setHeader("WWW-Authenticate", "Basic realm=\"ledgercore\"");
			errors.write(request, response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
					"Valid credentials are required.");
		};
		AccessDeniedHandler denied = (request, response, ex) -> errors.write(request, response,
				HttpStatus.FORBIDDEN, "FORBIDDEN_OPERATION", "Your credentials do not permit this operation.");

		http.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache(cache -> cache.disable())
			.httpBasic(basic -> basic.authenticationEntryPoint(unauthenticated))
			.exceptionHandling(handling -> handling.authenticationEntryPoint(unauthenticated)
				.accessDeniedHandler(denied))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
				// Before the /actuator/** rule: only the diagnostics login may read the Prometheus export.
				.requestMatchers("/actuator/prometheus").hasAuthority(DIAGNOSTICS_READER)
				.requestMatchers("/v1/**", "/actuator/**").hasAuthority(LEDGER_USER)
				.requestMatchers("/demo-consumer/**").hasAuthority(EVENT_PUBLISHER)
				.anyRequest().denyAll());
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	/** Builds logins from configuration. A user without a configured hash cannot sign in. */
	@Bean
	UserDetailsService ledgerUsers(LedgerProperties properties) {
		List<UserDetails> users = new ArrayList<>();
		for (LedgerProperties.UserAccount account : properties.security().users()) {
			if (account.passwordHash() == null || account.passwordHash().isBlank()) {
				log.warn("No password hash configured for user '{}'; that user cannot sign in.", account.username());
				continue;
			}
			users.add(User.withUsername(account.username())
				.password(account.passwordHash())
				.authorities(LEDGER_USER)
				.build());
		}
		LedgerProperties.DemoConsumer consumer = properties.demoConsumer();
		if (consumer != null && consumer.passwordHash() != null && !consumer.passwordHash().isBlank()) {
			users.add(User.withUsername(consumer.username())
				.password(consumer.passwordHash())
				.authorities(EVENT_PUBLISHER)
				.build());
		}
		else {
			log.warn("No demonstration consumer credential configured; outbox deliveries will be rejected.");
		}
		LedgerProperties.Diagnostics diagnostics = properties.diagnostics();
		if (diagnostics != null && diagnostics.passwordHash() != null && !diagnostics.passwordHash().isBlank()) {
			users.add(User.withUsername(diagnostics.username())
				.password(diagnostics.passwordHash())
				.authorities(DIAGNOSTICS_READER)
				.build());
		}
		InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager(users);
		// Expose only lookup. InMemoryUserDetailsManager also implements UserDetailsPasswordService,
		// which makes Spring Security silently re-hash any weaker configured hash at its default cost
		// after the first login. The configured hash, and therefore the configured cost, must stay the
		// one actually verified on every request.
		return manager::loadUserByUsername;
	}

}
