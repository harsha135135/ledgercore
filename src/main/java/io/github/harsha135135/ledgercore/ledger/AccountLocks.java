package io.github.harsha135135.ledgercore.ledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * The only way services lock accounts. Every path locks in the same total order, so two
 * transactions touching the same pair of accounts cannot each hold one lock while waiting for
 * the other. That reduces deadlock risk; lock timeouts still bound any wait that remains.
 *
 * <p>The order is the canonical lowercase {@code UUID.toString()} form compared with
 * {@code String.compareTo}. {@code UUID.compareTo} is deliberately not used: it compares signed
 * 64-bit halves, so IDs starting with hex 8-f sort before 0-7 and the two orders disagree.
 * Mixing them on different paths could deadlock.
 */
@Component
public class AccountLocks {

	private final AccountRepository accounts;

	public AccountLocks(AccountRepository accounts) {
		this.accounts = accounts;
	}

	/**
	 * Locks each distinct account, one row at a time, in canonical order. Must run inside a
	 * transaction; the locks are held until it commits or rolls back.
	 */
	public Map<UUID, AccountRow> lockInCanonicalOrder(Collection<UUID> accountIds) {
		Map<UUID, AccountRow> lockedById = new LinkedHashMap<>();
		for (UUID accountId : canonicalLockOrder(accountIds)) {
			lockedById.put(accountId, accounts.lockById(accountId));
		}
		return lockedById;
	}

	/** Removes duplicates and sorts by canonical string form. */
	static List<UUID> canonicalLockOrder(Collection<UUID> accountIds) {
		TreeMap<String, UUID> byCanonicalText = new TreeMap<>();
		for (UUID accountId : accountIds) {
			byCanonicalText.put(accountId.toString(), accountId);
		}
		return new ArrayList<>(byCanonicalText.values());
	}

}
