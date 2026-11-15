-- Run ONCE per MySQL/MariaDB server as an administrator (edit the password first):
--   mysql -u root -p < scripts/init-db.sql
-- EACH BRANCH NODE OWNS ITS OWN DATABASE (the two branches never share one database - they replicate through RMI).
--   * Both nodes on ONE laptop (single-machine demo): use the two databases below.
--   * One node per laptop: create only the database that node uses (nexus_banking_a or nexus_banking_b).
-- Tables are created and the 7 demo accounts are seeded by the server at first start (database.schema.auto=true).
-- To keep DDL out of the application account, set database.schema.auto=false and run
-- banking-server/src/main/resources/db/db-schema.sql yourself.
CREATE DATABASE IF NOT EXISTS nexus_banking_a CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nexus_banking_b CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'nexus_user'@'%' IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';
-- TRIGGER lets the server make the audit ledger append-only at database level (optional, best effort).
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, TRIGGER ON nexus_banking_a.* TO 'nexus_user'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, TRIGGER ON nexus_banking_b.* TO 'nexus_user'@'%';
-- Tighten the host part ('%') to the server's address in production.
FLUSH PRIVILEGES;
