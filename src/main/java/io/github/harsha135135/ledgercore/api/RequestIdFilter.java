package io.github.harsha135135.ledgercore.api;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a server-generated ID before security runs, so even a 401 response
 * carries one. The ID appears in logs, error bodies and the X-Request-Id response header.
 * Client-supplied IDs are ignored to keep log correlation trustworthy.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = UUID.randomUUID().toString();
		request.setAttribute(RequestIds.ATTRIBUTE, requestId);
		response.setHeader(RequestIds.HEADER, requestId);
		MDC.put("requestId", requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove("requestId");
		}
	}

}
