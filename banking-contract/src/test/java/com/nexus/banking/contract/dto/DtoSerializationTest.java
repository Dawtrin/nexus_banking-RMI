package com.nexus.banking.contract.dto;

import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.contract.enums.ServerStatus;
import com.nexus.banking.contract.enums.SessionStatus;
import com.nexus.banking.contract.enums.TransactionSyncState;
import com.nexus.banking.contract.enums.TransactionType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DtoSerializationTest {

    @SuppressWarnings("unchecked")
    private <T> T roundTrip(T original) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            return (T) ois.readObject();
        }
    }

    @Test
    void transactionSurvivesRmiSerialization() throws Exception {
        TransactionDTO original = new TransactionDTO(UUID.randomUUID().toString(), "1001000001", TransactionType.TRANSFER,
                new BigDecimal("1500000.00"), new BigDecimal("48500000.00"), "2002000001", "tien nha", Instant.now(),
                "server-a", 101L, 42L, "req-1", TransactionSyncState.SYNCED);
        TransactionDTO copy = roundTrip(original);
        assertThat(copy).isEqualTo(original);
        assertThat(copy.getAmount()).isEqualByComparingTo("1500000");
        assertThat(copy.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(copy.getOriginSequence()).isEqualTo(101L);
        assertThat(copy.getSyncState()).isEqualTo(TransactionSyncState.SYNCED);
        assertThat(copy.getSignedAmount()).isEqualByComparingTo("-1500000");
    }

    @Test
    void creditLegHasPositiveSignedAmount() {
        TransactionDTO in = new TransactionDTO("t1", "2002000001", TransactionType.TRANSFER_IN, new BigDecimal("10.00"),
                new BigDecimal("20.00"), "1001000001", "", Instant.now(), "server-a", 2, 0, null, null);
        assertThat(in.getSignedAmount()).isEqualByComparingTo("10");
        assertThat(in.getType().isCredit()).isTrue();
        assertThat(in.getType().isOutgoing()).isFalse();
    }

    @Test
    void accountAndSessionSerialize() throws Exception {
        AccountDTO acc = new AccountDTO("1001000001", "Nguyễn Văn An", new BigDecimal("50000000.00"), "HN",
                AccountStatus.ACTIVE, Instant.now(), new BigDecimal("20000000.00"));
        AccountDTO accCopy = roundTrip(acc);
        assertThat(accCopy.getOwnerName()).isEqualTo("Nguyễn Văn An");
        assertThat(accCopy.getBalance()).isEqualByComparingTo("50000000");

        ClientSessionDTO s = new ClientSessionDTO("sess-abc-123", "1001000001", "Nguyễn Văn An", "device-1",
                "192.168.1.50", Instant.now(), Instant.now(), SessionStatus.ACTIVE, "server-a", true);
        ClientSessionDTO sCopy = roundTrip(s);
        assertThat(sCopy).isEqualTo(s);
        assertThat(sCopy.isPushEnabled()).isTrue();
        assertThat(sCopy.getAccountNumber()).isEqualTo("1001000001");
    }

    @Test
    void operationResultCarriesGenericPayload() throws Exception {
        AccountDTO acc = new AccountDTO("1", "A", BigDecimal.TEN, "HN", AccountStatus.ACTIVE, Instant.now(), null);
        OperationResult<AccountDTO> res = roundTrip(OperationResult.success("ok", acc));
        assertThat(res.isSuccess()).isTrue();
        assertThat(res.getData()).isEqualTo(acc);
        OperationResult<Void> fail = roundTrip(OperationResult.failure(ErrorCodes.INSUFFICIENT_FUNDS, "Không đủ số dư"));
        assertThat(fail.isSuccess()).isFalse();
        assertThat(fail.getErrorCode()).isEqualTo(ErrorCodes.INSUFFICIENT_FUNDS);
    }

    @Test
    void serverStatusKeepsBranchName() throws Exception {
        ServerStatusDTO st = new ServerStatusDTO("server-a", "node-a.local", "192.168.1.101", "0.0.0.0", "192.168.1.101",
                1099, 1100, ServerStatus.ONLINE, ServerStatus.ONLINE, ServerStatus.ONLINE, 3600L, Instant.now(), 5, 3,
                Instant.now(), "MYSQL_DURABLE", "Chi nhánh Hà Nội");
        ServerStatusDTO copy = roundTrip(st);
        assertThat(copy.getBranchName()).isEqualTo("Chi nhánh Hà Nội");
        assertThat(copy.getPendingReplicationCount()).isEqualTo(3);
        assertThat(new ServerStatusDTO("s", null, null, null, null, 1, 2, null, null, null, 0, null, 0).getBranchName())
                .isEqualTo("s");
    }

    @Test
    void ledgerAuditAndNotificationSerialize() throws Exception {
        LedgerSummaryDTO sum = new LedgerSummaryDTO("server-a", 6, 1, new BigDecimal("100.00"), 12,
                Map.of("server-a", 7L, "server-b", 5L), "abc", List.of("1001"), Instant.now());
        LedgerSummaryDTO sumCopy = roundTrip(sum);
        assertThat(sumCopy.getLastSequenceByOrigin()).containsEntry("server-b", 5L);
        assertThat(sumCopy.getOverdrawnAccounts()).containsExactly("1001");

        AuditEntryDTO a = new AuditEntryDTO(1, Instant.now(), "server-a", "op", "LOCK_ACCOUNT", "1001", "OK", "", "", "h");
        assertThat(roundTrip(a).getHash()).isEqualTo("h");

        BalanceNotificationDTO n = new BalanceNotificationDTO("1001", new BigDecimal("5.00"), new BigDecimal("15.00"),
                "t", TransactionType.DEPOSIT, "", "", "server-a", false, Instant.now());
        assertThat(roundTrip(n).getDelta()).isEqualByComparingTo("5");

        ServerEventDTO ev = new ServerEventDTO(UUID.randomUUID().toString(), Instant.now(), EventSeverity.INFO, "RMI",
                "Registry bound", "server-a");
        assertThat(roundTrip(ev).getSourceComponent()).isEqualTo("RMI");
    }
}
