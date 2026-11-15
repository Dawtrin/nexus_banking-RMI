package com.nexus.banking.server.store;

/** Something worth an audit record that the store noticed while applying replicated entries. */
public record LedgerEvent(Kind kind, String account, String details) {
    public enum Kind {
        /** A peer entry carried a client request id that this node had already executed locally: not applied twice. */
        DUPLICATE_SUPPRESSED,
        /** After applying a peer debit the balance became negative (two branches spent the same money). */
        OVERDRAFT_DETECTED
    }
}
