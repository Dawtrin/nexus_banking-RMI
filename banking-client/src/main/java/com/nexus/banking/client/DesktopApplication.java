package com.nexus.banking.client;

import com.nexus.banking.client.navigation.ViewId;
import com.nexus.banking.client.navigation.ViewRouter;
import com.nexus.banking.client.service.ChatClientService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main JavaFX application entrypoint for NEXUS CHAT.
 */
public class DesktopApplication extends Application {
    private static final Logger log = LoggerFactory.getLogger(DesktopApplication.class);

    private ChatClientService chatService;
    private ViewRouter router;

    @Override
    public void start(Stage primaryStage) {
        log.info("Starting NEXUS CHAT JavaFX Desktop Client...");

        this.chatService = new ChatClientService();
        this.router = new ViewRouter(primaryStage, chatService);

        primaryStage.setMinWidth(960);
        primaryStage.setMinHeight(640);

        primaryStage.setOnCloseRequest(event -> {
            log.info("Window close requested. Shutting down client resources...");
            try {
                chatService.close();
            } catch (Exception e) {
                log.warn("Error during client shutdown: {}", e.getMessage());
            }
            Platform.exit();
            System.exit(0);
        });

        // Launch into Login view
        router.navigateTo(ViewId.LOGIN);
    }

    public static void main(String[] args) {
        com.nexus.banking.contract.rmi.RmiTimeouts.install();
        launch(args);
    }
}
