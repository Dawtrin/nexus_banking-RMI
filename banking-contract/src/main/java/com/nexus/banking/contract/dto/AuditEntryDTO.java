package com.nexus.banking.contract.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * One record of the append-only audit ledger. Every entry stores {@code prevHash} (the hash of the previous entry)
 * and its own {@code hash} = SHA-256 over the previous hash and all fields, so any later modification or deletion
 * breaks the chain and is detected by {@code verifyAuditChain}.
 */
public class AuditEntryDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 2L;

    private final long id;
    private final Instant timestamp;
    private final String serverId;
    private final String actor;
    private final String action;
    private final String target;
    private final String result;
    private final String details;
    private final String prevHash;
    private final String hash;

    public AuditEntryDTO(long id, Instant timestamp, String serverId, String actor, String action, String target,
                         String result, String details, String prevHash, String hash) {
        this.id = id;
        this.timestamp = timestamp;
        this.serverId = serverId;
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.result = result;
        this.details = details == null ? "" : details;
        this.prevHash = prevHash == null ? "" : prevHash;
        this.hash = hash == null ? "" : hash;
    }

    public long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getServerId() { return serverId; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getResult() { return result; }
    public String getDetails() { return details; }
    public String getPrevHash() { return prevHash; }
    public String getHash() { return hash; }

    /** The canonical text that is hashed (single definition shared by writer and verifier). */
    public static String canonical(String prevHash, long timestampMillis, String serverId, String actor, String action,
                                   String target, String result, String details) {
        return String.join("|", prevHash, Long.toString(timestampMillis), serverId, actor, action, target, result, details);
    }
}
