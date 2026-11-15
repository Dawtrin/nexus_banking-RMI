package com.nexus.banking.server.store;

import com.nexus.banking.contract.enums.AccountStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Demo accounts that every node seeds with IDENTICAL opening balances (the opening balance is not a ledger entry:
 * both branches start from the same books and then exchange ledger entries). All demo PINs are 123456.
 */
public final class DefaultAccounts {
    public static final String DEMO_PIN = "123456";

    public record Seed(String accountNumber, String ownerName, String balance, String branchId, AccountStatus status,
                       String dailyLimit) {
        public BigDecimal balanceValue() { return new BigDecimal(balance); }
        public BigDecimal limitValue() { return new BigDecimal(dailyLimit); }
    }

    private DefaultAccounts() {
    }

    public static List<Seed> list() {
        return List.of(
                new Seed("1001000001", "Nguyễn Văn An", "50000000.00", "HN", AccountStatus.ACTIVE, "20000000.00"),
                new Seed("1001000002", "Trần Thị Bình", "25000000.00", "HN", AccountStatus.ACTIVE, "20000000.00"),
                new Seed("2002000001", "Lê Hoàng Cường", "80000000.00", "HCM", AccountStatus.ACTIVE, "30000000.00"),
                new Seed("2002000002", "Phạm Minh Dũng", "12500000.00", "HCM", AccountStatus.ACTIVE, "10000000.00"),
                new Seed("3003000001", "Võ Thanh Em", "5000000.00", "HN", AccountStatus.ACTIVE, "5000000.00"),
                new Seed("9009000001", "Công ty TNHH NEXUS", "1000000000.00", "HCM", AccountStatus.ACTIVE, "500000000.00"),
                new Seed("4004000001", "Hoàng Văn Khóa (demo bị khóa)", "1000000.00", "HN", AccountStatus.LOCKED, "5000000.00"));
    }
}
