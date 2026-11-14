package com.nexus.banking.contract.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/** One persisted audit record of an administrative action or security-relevant event. */
public class AuditEntryDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final long id;
    private final Instant timestamp;
    private final String serverId;
    private final String actor;
    private final String action;
    private final String target;
    private final String result;
    private final String details;

    public AuditEntryDTO(long id, Instant timestamp, String serverId, String actor, String action, String target,
                         String result, String details) {
        this.id = id;
        this.timestamp = timestamp;
        this.serverId = serverId;
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.result = result;
        this.details = details == null ? "" : details;
    }

    public long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getServerId() { return serverId; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getResult() { return result; }
    public String getDetails() { return details; }
}
