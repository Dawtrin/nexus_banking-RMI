package com.nexus.banking.client;

import com.nexus.banking.client.config.ClientPreferences;
import com.nexus.banking.client.core.BankingClientCore;
import com.nexus.banking.client.rmi.CallbackExporter;
import com.nexus.banking.client.rmi.RmiClientManager;
import com.nexus.banking.client.ui.BankingUi;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JavaFX entry point of NEXUS BANKING (customer Internet Banking / ATM app). */
public class BankingApp extends Application {
    private static final Logger log = LoggerFactory.getLogger(BankingApp.class);
    private BankingClientCore core;

    @Override
    public void start(Stage stage) {
        log.info("Starting NEXUS BANKING client...");
        BankingUi ui = new BankingUi(stage);
        core = BankingClientCore.start((host, port) -> RmiClientManager.connect(host, port, ClientPreferences.sslEnabled()),
                new CallbackExporter(ClientPreferences::sslEnabled), ui.listener(), 2000);
        core.setAutoReconnect(ClientPreferences.autoReconnect());
        ui.bind(core);
        stage.setOnCloseRequest(e -> {
            try {
                core.close();
            } catch (Exception ex) {
                log.warn("Error during shutdown: {}", ex.getMessage());
            }
            Platform.exit();
            System.exit(0);
        });
        ui.showLogin(null);
        stage.show();
    }

    public static void main(String[] args) {
        com.nexus.banking.contract.rmi.RmiTimeouts.install();
        launch(args);
    }

}
