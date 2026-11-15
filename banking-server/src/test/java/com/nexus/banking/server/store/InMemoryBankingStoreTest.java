package com.nexus.banking.server.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryBankingStoreTest extends BankingStoreContractTest {
    @Override
    protected BankingStore store() {
        return new InMemoryBankingStore();
    }

    @Test
    void tamperingWithAnAuditEntryBreaksTheChain() {
        InMemoryBankingStore s = new InMemoryBankingStore();
        for (int i = 0; i < 4; i++) s.appendAudit(Instant.now(), "n-a", "ops", "LOCK_ACCOUNT", "acc" + i, "OK", "reason " + i);
        s.tamperAuditForTest(1, "nothing happened here");
        var v = s.verifyAuditChain();
        assertThat(v.isValid()).isFalse();
        assertThat(v.getFirstBrokenId()).isEqualTo(2);
        assertThat(v.getEntriesChecked()).isEqualTo(1);
    }

    @Test
    void ramModeIsHonestAboutDurability() {
        assertThat(new InMemoryBankingStore().isDurable()).isFalse();
        assertThat(new InMemoryBankingStore().modeLabel()).isEqualTo("RAM_ONLY_DEMO");
    }
}
