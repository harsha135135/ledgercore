package io.github.harsha135135.ledgercore.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PrincipalRepository {

	private final JdbcTemplate jdbc;

	public PrincipalRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<UUID> findIdBySubject(String externalSubject) {
		List<UUID> ids = jdbc.queryForList("SELECT id FROM principals WHERE external_subject = ?", UUID.class,
				externalSubject);
		return ids.stream().findFirst();
	}

	public Optional<PrincipalRow> findById(UUID principalId) {
		List<PrincipalRow> rows = jdbc.query("SELECT id, role FROM principals WHERE id = ?",
				(rs, rowNum) -> new PrincipalRow(rs.getObject("id", UUID.class), Role.valueOf(rs.getString("role"))),
				principalId);
		return rows.stream().findFirst();
	}

	/**
	 * Loads the caller's current role. Called at the start of every operation and again inside
	 * each mutation transaction, so a role change applies to the next request, including replays.
	 */
	public PrincipalRow requireCurrent(UUID principalId) {
		return findById(principalId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
				"PRINCIPAL_NOT_PROVISIONED", "Your login is not mapped to a ledger principal."));
	}

}
