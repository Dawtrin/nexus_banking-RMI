package com.nexus.banking.client.controller;

import com.nexus.banking.client.service.ChatClientService;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** About: version and the facts of the running session. */
public class AboutPaneController implements ScreenController {
    private final ChatClientService service;

    @FXML private Label versionLabel;
    @FXML private Label sessionLabel;
    @FXML private Label techLabel;

    public AboutPaneController(ChatClientService service) {
        this.service = service;
    }

    @FXML
    public void initialize() {
        String v = AboutPaneController.class.getPackage().getImplementationVersion();
        versionLabel.setText("Version " + (v == null ? "1.0.0-SNAPSHOT" : v));
        techLabel.setText("Java " + System.getProperty("java.version") + "  -  JavaFX " + System.getProperty("javafx.version", "unknown")
                + "  -  Java RMI  -  MySQL / MariaDB");
    }

    @Override
    public void onShow() {
        sessionLabel.setText(service.currentUsernameProperty().get() + " on " + service.currentServerIdProperty().get() + "  -  "
                + service.connectionStateProperty().get().getLabel());
    }
}
