package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.AuditVerificationDTO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Hash chain of the audit ledger: each entry commits to its predecessor, so tampering is detectable. */
public final class AuditChain {
    public static final String GENESIS = "0".repeat(64);

    private AuditChain() {
    }

    public static String hash(String prevHash, Instant at, String serverId, String actor, String action, String target,
                              String result, String details) {
        String text = AuditEntryDTO.canonical(prevHash, at.toEpochMilli(), serverId, actor, action, target, result, details);
        return sha256(text);
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Verifies entries given oldest first. */
    public static AuditVerificationDTO verify(List<AuditEntryDTO> oldestFirst) {
        String prev = GENESIS;
        long checked = 0;
        for (AuditEntryDTO e : oldestFirst) {
            String expected = hash(prev, e.getTimestamp(), e.getServerId(), e.getActor(), e.getAction(), e.getTarget(),
                    e.getResult(), e.getDetails());
            if (!prev.equals(e.getPrevHash()) || !expected.equals(e.getHash())) {
                return new AuditVerificationDTO(false, checked, e.getId(), prev,
                        "Chuỗi băm bị phá vỡ tại bút toán kiểm toán #" + e.getId() + ": dữ liệu đã bị sửa hoặc xóa");
            }
            prev = e.getHash();
            checked++;
        }
        return new AuditVerificationDTO(true, checked, -1, prev,
                checked == 0 ? "Sổ kiểm toán trống" : "Toàn vẹn: " + checked + " bút toán, chuỗi băm liên tục");
    }
}
