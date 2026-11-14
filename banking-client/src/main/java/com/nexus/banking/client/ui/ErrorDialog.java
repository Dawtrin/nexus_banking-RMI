package com.nexus.banking.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/** Themed modal error dialog (connection errors etc.). */
public final class ErrorDialog {
    private ErrorDialog() {
    }

    public static void show(Window owner, String title, String message) {
        Stage stage = new Stage(StageStyle.UTILITY);
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(title);
        Circle badge = new Circle(26, Color.web("#ef4444"));
        Label bang = new Label("!");
        bang.setStyle("-fx-text-fill: white; -fx-font-size: 28px; -fx-font-weight: bold;");
        StackPane icon = new StackPane(badge, bang);
        Label t = new Label(title);
        t.getStyleClass().add("title-medium");
        Label m = new Label(message);
        m.setWrapText(true);
        m.getStyleClass().add("text-secondary");
        m.setMaxWidth(340);
        VBox text = new VBox(8, t, m);
        HBox top = new HBox(18, icon, text);
        top.setAlignment(Pos.CENTER_LEFT);
        Button ok = new Button("OK");
        ok.getStyleClass().add("btn-primary");
        ok.setDefaultButton(true);
        ok.setOnAction(e -> stage.close());
        HBox bottom = new HBox(ok);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(20, top, bottom);
        root.setPadding(new Insets(24));
        root.getStyleClass().add("app-shell");
        root.setMinWidth(460);
        Scene scene = new Scene(root);
        scene.getStylesheets().add(ErrorDialog.class.getResource("/com/nexus/banking/client/css/design-system.css").toExternalForm());
        scene.getStylesheets().add(ErrorDialog.class.getResource("/com/nexus/banking/client/css/nexus-chat.css").toExternalForm());
        stage.setScene(scene);
        stage.showAndWait();
    }
}
