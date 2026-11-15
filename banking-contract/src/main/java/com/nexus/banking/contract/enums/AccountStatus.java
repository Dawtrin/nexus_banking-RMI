package com.nexus.banking.contract.enums;

import java.io.Serializable;

/** Operational status of a bank account. A LOCKED account rejects every money movement and login. */
public enum AccountStatus implements Serializable {
    ACTIVE,
    LOCKED
}
