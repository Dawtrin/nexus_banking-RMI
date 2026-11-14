package com.nexus.banking.client.navigation;

/** Top-level windows contents of NEXUS CHAT. The five main screens live inside MAIN. */
public enum ViewId {
    LOGIN("/com/nexus/banking/client/fxml/login-view.fxml", "NEXUS CHAT - Sign in"),
    MAIN("/com/nexus/banking/client/fxml/main-shell.fxml", "NEXUS CHAT");

    private final String fxmlPath;
    private final String title;

    ViewId(String fxmlPath, String title) {
        this.fxmlPath = fxmlPath;
        this.title = title;
    }

    public String getFxmlPath() { return fxmlPath; }
    public String getTitle() { return title; }
}
