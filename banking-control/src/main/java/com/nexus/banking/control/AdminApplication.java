package com.nexus.banking.control;

import com.nexus.banking.control.core.AdminRmi;
import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.ui.LoginController;
import com.nexus.banking.control.ui.ShellController;
import com.nexus.banking.contract.rmi.RmiTimeouts;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** NEXUS CONTROL: its own application, window and sign-in. It shares only the contract with NEXUS BANKING. */
public class AdminApplication extends Application {
    private final AdminSession session = new AdminSession(AdminRmi::connect);
    private Stage stage;
    private Scene scene;
    private ShellController shell;

    @Override
    public void start(Stage primary) throws Exception {
        this.stage = primary;
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        Parent login = load("login-view.fxml", t -> new LoginController(session, this::openShell));
        scene = new Scene(login, Math.min(1280, vb.getWidth()), Math.min(800, vb.getHeight()));
        scene.getStylesheets().add(getClass().getResource("/com/nexus/banking/control/css/design-system.css").toExternalForm());
        scene.getStylesheets().add(getClass().getResource("/com/nexus/banking/control/css/nexus-control.css").toExternalForm());
        stage.setTitle("NEXUS CONTROL");
        stage.setMinWidth(1000);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (shell != null) shell.stop();
            session.signOut();
            Platform.exit();
        });
        stage.show();
    }

    private Parent load(String fxml, javafx.util.Callback<Class<?>, Object> factory) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/nexus/banking/control/fxml/" + fxml));
        loader.setControllerFactory(factory);
        return loader.load();
    }

    private void openShell() {
        try {
            shell = null;
            Parent root = load("shell-view.fxml", t -> {
                shell = new ShellController(session, this::signOut);
                return shell;
            });
            scene.setRoot(root);
            stage.setTitle("NEXUS CONTROL - " + session.operator());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot open the control window: " + e.getMessage(), e);
        }
    }

    private void signOut() {
        try {
            if (shell != null) shell.stop();
            session.signOut();
            scene.setRoot(load("login-view.fxml", t -> new LoginController(session, this::openShell)));
            stage.setTitle("NEXUS CONTROL");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) {
        RmiTimeouts.install();
        launch(args);
    }
}
