package com.nexus.banking.client.controller;

import com.nexus.banking.client.config.ClientPreferences;
import com.nexus.banking.client.navigation.ViewRouter;
import com.nexus.banking.client.service.ChatClientService;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Settings: connection, text size, notifications, security notes. Everything is stored locally. */
public class SettingsPaneController implements ScreenController {
    private final ChatClientService service;
    private VBox[] panes;

    @FXML private VBox sections;
    @FXML private VBox connectionSection;
    @FXML private VBox appearanceSection;
    @FXML private VBox notificationSection;
    @FXML private VBox securitySection;
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private CheckBox autoReconnect;
    @FXML private Label savedLabel;
    @FXML private HBox errorBanner;
    @FXML private Label errorLabel;
    @FXML private ComboBox<String> fontCombo;
    @FXML private CheckBox unreadTitle;
    @FXML private CheckBox beep;
    @FXML private Label forgetLabel;

    public SettingsPaneController(ChatClientService service) {
        this.service = service;
    }

    @FXML
    public void initialize() {
        VBox menu = sections;
        panes = new VBox[]{connectionSection, appearanceSection, notificationSection, securitySection};
        String[] names = {"Server connection", "Appearance", "Notifications", "Security"};
        ToggleGroup g = new ToggleGroup();
        for (int i = 0; i < names.length; i++) {
            ToggleButton b = new ToggleButton(names[i]);
            b.getStyleClass().add("nav-item");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setToggleGroup(g);
            final int idx = i;
            b.setOnAction(e -> showSection(idx));
            menu.getChildren().add(b);
            if (i == 0) b.setSelected(true);
        }
        g.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) was.setSelected(true);
        });
        fontCombo.getItems().addAll("Small", "Normal", "Large");
        fontCombo.valueProperty().addListener((o, was, now) -> {
            if (now == null) return;
            ClientPreferences.setFontSize(now.toUpperCase());
            if (fontCombo.getScene() != null) ViewRouter.applyFontSize(fontCombo.getScene().getRoot());
        });
    }

    private void showSection(int idx) {
        for (int i = 0; i < panes.length; i++) {
            panes[i].setVisible(i == idx);
            panes[i].setManaged(i == idx);
        }
    }

    @Override
    public void onShow() {
        hostField.setText(ClientPreferences.host());
        portField.setText(String.valueOf(ClientPreferences.port()));
        autoReconnect.setSelected(ClientPreferences.autoReconnect());
        String f = ClientPreferences.fontSize();
        fontCombo.getSelectionModel().select(f.charAt(0) + f.substring(1).toLowerCase());
        unreadTitle.setSelected(ClientPreferences.unreadInTitle());
        beep.setSelected(ClientPreferences.beepOnMessage());
        savedLabel.setText("");
        forgetLabel.setText("");
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);
    }

    @FXML
    public void onSave() {
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);
        String host = hostField.getText() == null ? "" : hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (host.isEmpty() || port < 1 || port > 65535) {
            errorLabel.setText("Enter the server address and a port between 1 and 65535.");
            errorBanner.setVisible(true);
            errorBanner.setManaged(true);
            return;
        }
        ClientPreferences.remember(host, port, ClientPreferences.username());
        ClientPreferences.setAutoReconnect(autoReconnect.isSelected());
        service.setAutoReconnect(autoReconnect.isSelected());
        savedLabel.setText("Saved. The server applies from your next sign-in; reconnect behaviour applies now.");
    }

    @FXML
    public void onNotificationChanged() {
        ClientPreferences.setUnreadInTitle(unreadTitle.isSelected());
        ClientPreferences.setBeepOnMessage(beep.isSelected());
    }

    @FXML
    public void onForget() {
        ClientPreferences.forgetConnection();
        forgetLabel.setText("Forgotten. The sign-in screen will start empty next time.");
    }
}
