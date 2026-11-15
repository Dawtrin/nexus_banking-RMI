package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.AccountDTO;

/** What login needs: the account plus its salted PIN hash (never leaves the server). */
public record StoredCredential(AccountDTO account, String pinHash) {
}
