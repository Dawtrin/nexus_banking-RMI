package com.nexus.banking.contract.dto;

import java.io.Serial;
import java.io.Serializable;

/** Result of a successful login: the server-side session, the account and the branch that serves the customer. */
public class LoginResultDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final ClientSessionDTO session;
    private final AccountDTO account;
    private final String serverId;
    private final String branchName;
    private final boolean callbackRegistered;

    public LoginResultDTO(ClientSessionDTO session, AccountDTO account, String serverId, String branchName,
                          boolean callbackRegistered) {
        this.session = session;
        this.account = account;
        this.serverId = serverId;
        this.branchName = branchName;
        this.callbackRegistered = callbackRegistered;
    }

    public ClientSessionDTO getSession() { return session; }
    public AccountDTO getAccount() { return account; }
    public String getServerId() { return serverId; }
    public String getBranchName() { return branchName; }
    public boolean isCallbackRegistered() { return callbackRegistered; }
}
