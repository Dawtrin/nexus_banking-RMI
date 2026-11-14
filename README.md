# NEXUS Banking (RMI) — Distributed Core-Banking & ATM Cluster

Refactored from `nexus_chat-RMI` (kept untouched). Java 21, Maven multi-module, Java RMI + TLS/mTLS,
JavaFX, MySQL, Transactional Outbox, catch-up sync, single-session eviction.

| Module | Role |
|---|---|
| `banking-contract` | RMI interfaces, DTOs, TLS socket factories |
| `banking-server` | Cluster node (Node A – Hà Nội, Node B – TP.HCM): services, stores, replication |
| `banking-client` | JavaFX Internet Banking / ATM app for customers |
| `banking-control` | JavaFX operations & monitoring center |

Root package: `com.nexus.banking` (`.contract`, `.server`, `.client`, `.control`).

## Status
- [x] Step 1 — repo bootstrap, module/package rename, compiles
- [ ] Step 2 — domain & schema (accounts, transactions, outbox, audit)
- [ ] Step 3 — server engine (idempotent atomic money movement)
- [ ] Step 4 — JavaFX UIs
- [ ] Step 5 — scripts & demo scenario

Build: `mvn clean compile`
