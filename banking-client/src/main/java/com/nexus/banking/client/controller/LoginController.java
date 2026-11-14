package com.nexus.banking.client.controller;

import com.nexus.banking.client.config.ClientConfig;
import com.nexus.banking.client.config.ClientPreferences;
import com.nexus.banking.client.navigation.ViewId;
import com.nexus.banking.client.navigation.ViewRouter;
import com.nexus.banking.client.service.ChatClientService;
import com.nexus.banking.client.ui.ErrorDialog;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

/** Sign-in screen: username, server choice, remembered connection, honest loading and error feedback. */
public class LoginController {
    private static final String A = "This computer - server A (port 1099)";
    private static final String B = "This computer - server B (port 2099)";
    private static final String CUSTOM = "Another computer...";

    private final ChatClientService service;
    private final ViewRouter router;

    @FXML private TextField usernameField;
    @FXML private ComboBox<String> serverCombo;
    @FXML private HBox customRow;
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private CheckBox rememberCheck;
    @FXML private CheckBox sslCheck;
    @FXML private HBox errorBanner;
    @FXML private Label errorLabel;
    @FXML private Button signInButton;
    @FXML private ProgressIndicator progress;
    @FXML private Label statusLabel;
    @FXML private Label securityNoticeLabel;

    public LoginController(ChatClientService service, ViewRouter router) {
        this.service = service;
        this.router = router;
    }

    @FXML
    public void initialize() {
        serverCombo.getItems().addAll(A, B, CUSTOM);
        String host = ClientPreferences.host();
        int port = ClientPreferences.port();
        int profile = host.equals(ClientConfig.DEFAULT_SERVER_A_HOST) && port == ClientConfig.DEFAULT_SERVER_A_PORT ? 0
                : host.equals(ClientConfig.DEFAULT_SERVER_B_HOST) && port == ClientConfig.DEFAULT_SERVER_B_PORT ? 1 : 2;
        serverCombo.getSelectionModel().select(profile);
        hostField.setText(host);
        portField.setText(String.valueOf(port));
        showCustom(profile == 2);
        serverCombo.valueProperty().addListener((o, was, now) -> showCustom(CUSTOM.equals(now)));
        rememberCheck.setSelected(ClientPreferences.rememberConnection());
        sslCheck.setSelected(ClientPreferences.sslEnabled());
        sslCheck.selectedProperty().addListener((o, was, now) -> updateSecurityNotice(now));
        updateSecurityNotice(sslCheck.isSelected());
        usernameField.setText(ClientPreferences.username());
        statusLabel.setText("");
    }

    private void updateSecurityNotice(boolean ssl) {
        if (securityNoticeLabel != null) {
            if (ssl) {
                securityNoticeLabel.setText("Connection encrypted with TLS/SSL. Protects traffic on public/untrusted networks.");
            } else {
                securityNoticeLabel.setText("Usernames are not passwords: anyone can pick any name. Connection is plain TCP.");
            }
        }
    }

    private void showCustom(boolean on) {
        customRow.setManaged(on);
        customRow.setVisible(on);
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorBanner.setManaged(true);
        errorBanner.setVisible(true);
    }

    private void clearError() {
        errorBanner.setManaged(false);
        errorBanner.setVisible(false);
    }

    private void setBusy(boolean busy) {
        signInButton.setDisable(busy);
        usernameField.setDisable(busy);
        serverCombo.setDisable(busy);
        sslCheck.setDisable(busy);
        progress.setManaged(busy);
        progress.setVisible(busy);
        signInButton.setText(busy ? "Signing in..." : "Sign in");
    }

    @FXML
    public void onSignIn() {
        clearError();
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        if (!username.matches("[\\p{L}\\p{N}_.\\-]{2,32}")) {
            showError("Use 2 to 32 characters: letters, digits, dot, dash or underscore. Spaces are not allowed.");
            usernameField.requestFocus();
            return;
        }
        String host;
        int port;
        String choice = serverCombo.getValue();
        if (A.equals(choice)) {
            host = ClientConfig.DEFAULT_SERVER_A_HOST;
            port = ClientConfig.DEFAULT_SERVER_A_PORT;
        } else if (B.equals(choice)) {
            host = ClientConfig.DEFAULT_SERVER_B_HOST;
            port = ClientConfig.DEFAULT_SERVER_B_PORT;
        } else {
            host = hostField.getText() == null ? "" : hostField.getText().trim();
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException e) {
                port = -1;
            }
            if (host.isEmpty() || port < 1 || port > 65535) {
                showError("Enter the server address and a port between 1 and 65535.");
                return;
            }
        }
        final String h = host;
        final int p = port;
        final boolean useSsl = sslCheck.isSelected();
        ClientPreferences.setSslEnabled(useSsl);

        setBusy(true);
        statusLabel.setText("Connecting to " + h + ":" + p + (useSsl ? " via TLS/SSL..." : "..."));
        service.connectAndRegister(h, p, username, result -> {
            setBusy(false);
            if (result.isSuccess()) {
                if (rememberCheck.isSelected()) ClientPreferences.remember(h, p, username);
                else ClientPreferences.forgetConnection();
                ClientPreferences.setRememberConnection(rememberCheck.isSelected());
                router.navigateTo(ViewId.MAIN);
            } else if ("CONNECTION_FAILED".equals(result.getErrorCode())) {
                statusLabel.setText("");
                ErrorDialog.show(signInButton.getScene().getWindow(), "Cannot reach the server",
                        "The server at " + h + ":" + p + " did not answer. Check the address, that the server is running, "
                                + (useSsl ? "that TLS/SSL is enabled on the server, " : "")
                                + "and that ports are open in the firewall.\n\nDetail: " + result.getMessage());
            } else {
                statusLabel.setText("");
                showError(result.getMessage());
            }
        });
    }
}
