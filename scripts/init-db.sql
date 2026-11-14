-- Run ONCE per database server as an administrator (edit names and password first):
--   mysql -u root -p < scripts/init-db.sql
-- The application account gets only what the server needs (tables are created by the server at first start
-- when database.schema.auto=true; set it to false and run banking-server/src/main/resources/db/db-schema.sql
-- yourself if you prefer to keep DDL out of the application account).
CREATE DATABASE IF NOT EXISTS nexus_chat CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'nexus_user'@'%' IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, CREATE VIEW ON nexus_chat.* TO 'nexus_user'@'%';
-- Tighten the host part ('%') to the server's address in production.
FLUSH PRIVILEGES;
