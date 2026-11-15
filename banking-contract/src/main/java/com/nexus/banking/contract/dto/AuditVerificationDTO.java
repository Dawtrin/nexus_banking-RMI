package com.nexus.banking.contract.dto;

import java.io.Serial;
import java.io.Serializable;

/** Result of re-computing the audit hash chain of one node. */
public class AuditVerificationDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean valid;
    private final long entriesChecked;
    private final long firstBrokenId;
    private final String headHash;
    private final String message;

    public AuditVerificationDTO(boolean valid, long entriesChecked, long firstBrokenId, String headHash, String message) {
        this.valid = valid;
        this.entriesChecked = entriesChecked;
        this.firstBrokenId = firstBrokenId;
        this.headHash = headHash == null ? "" : headHash;
        this.message = message == null ? "" : message;
    }

    public boolean isValid() { return valid; }
    public long getEntriesChecked() { return entriesChecked; }
    /** Id of the first entry whose hash or back-link does not match, or -1 when the chain is intact. */
    public long getFirstBrokenId() { return firstBrokenId; }
    public String getHeadHash() { return headHash; }
    public String getMessage() { return message; }
}
