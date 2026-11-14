package com.nexus.banking.client.navigation;

import com.nexus.banking.client.controller.MainShellController;
import com.nexus.banking.client.controller.LoginController;
import com.nexus.banking.client.service.ChatClientService;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;

/**
 * Manages view transitions and controller dependency injection.
 */
public class ViewRouter {
    private static final Logger log = LoggerFactory.getLogger(ViewRouter.class);

    private final Stage stage;
    private final ChatClientService chatService;
    private Scene scene;

    public ViewRouter(Stage stage, ChatClientService chatService) {
        this.stage = stage;
        this.chatService = chatService;
    }

    public void navigateTo(ViewId viewId) {
        try {
            URL fxmlUrl = getClass().getResource(viewId.getFxmlPath());
            if (fxmlUrl == null) {
                throw new IOException("Cannot locate FXML resource: " + viewId.getFxmlPath());
            }

            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            loader.setControllerFactory(param -> createController(param));

            Parent root = loader.load();

            if (scene == null) {
                javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
                scene = new Scene(root, Math.min(1180, vb.getWidth()), Math.min(760, vb.getHeight()));
                scene.getStylesheets().add(getClass().getResource("/com/nexus/banking/client/css/design-system.css").toExternalForm());
                scene.getStylesheets().add(getClass().getResource("/com/nexus/banking/client/css/nexus-chat.css").toExternalForm());
                stage.setMinWidth(900);
                stage.setMinHeight(600);
                stage.setScene(scene);
            } else {
                scene.setRoot(root);
            }

            stage.setTitle(viewId.getTitle());
            applyFontSize(root);
            stage.show();
            log.info("Navigated to {}", viewId);

        } catch (Exception e) {
            log.error("Failed navigating to {}: {}", viewId, e.getMessage(), e);
        }
    }

    /** Applies the saved font-size preference as a style class on the root. */
    public static void applyFontSize(Parent root) {
        root.getStyleClass().removeAll("font-small", "font-large");
        String size = com.nexus.banking.client.config.ClientPreferences.fontSize();
        if (size.equals("SMALL")) root.getStyleClass().add("font-small");
        else if (size.equals("LARGE")) root.getStyleClass().add("font-large");
    }

    private Object createController(Class<?> type) {
        if (type == LoginController.class) {
            return new LoginController(chatService, this);
        } else if (type == MainShellController.class) {
            return new MainShellController(chatService, this, stage);
        }
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Cannot instantiate controller " + type, e);
        }
    }
}
