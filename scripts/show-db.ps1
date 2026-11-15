# Shows the banking tables of one node's database (default: nexus_banking_a). Usage: .\scripts\show-db.ps1 [-Database nexus_banking_b]
param([string]$Database = 'nexus_banking_a', [string]$User = 'root', [string]$MysqlPath = 'mysql')
$q = @(
 @('BANK ACCOUNTS (bank_accounts)', "SELECT account_number, owner_name, balance, branch_id, status, daily_limit FROM bank_accounts ORDER BY account_number;"),
 @('LEDGER (bank_transactions)', "SELECT local_sequence AS seq, LEFT(transaction_id,8) AS txn, account_number, transaction_type AS type, amount, balance_after, reference_account AS ref, origin_server_id AS origin, origin_sequence AS o_seq, LEFT(client_request_id,8) AS req FROM bank_transactions ORDER BY local_sequence DESC LIMIT 20;"),
 @('REPLICATION OUTBOX (replication_outbox)', "SELECT outbox_id, LEFT(transaction_id,8) AS txn, target_peer_id, status, attempt_count, updated_at FROM replication_outbox ORDER BY outbox_id DESC LIMIT 10;"),
 @('AUDIT LEDGER (audit_log, hash-chained)', "SELECT audit_id, occurred_at, actor, action, target, result, LEFT(prev_hash,8) AS prev, LEFT(entry_hash,8) AS hash FROM audit_log ORDER BY audit_id DESC LIMIT 15;")
)
Write-Host "=== NEXUS BANKING database viewer: $Database ===" -ForegroundColor Cyan
foreach ($item in $q) {
    Write-Host "`n[$($item[0])]" -ForegroundColor Green
    & $MysqlPath -u $User -p $Database -e $item[1]
}
