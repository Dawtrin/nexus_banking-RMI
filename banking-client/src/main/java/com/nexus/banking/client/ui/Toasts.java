package com.nexus.banking.client.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Non-blocking notifications stacked in the top-right corner of the window. */
final class Toasts extends VBox {
    Toasts() {
        setSpacing(10);
        setPickOnBounds(false);
        setAlignment(Pos.TOP_RIGHT);
        setPadding(new Insets(84, 22, 0, 0));
        setMaxWidth(380);
        StackPane.setAlignment(this, Pos.TOP_RIGHT);
        setMouseTransparent(true);
    }

    /** kind: in (money received), out (money sent), info. */
    void show(String title, String message, String kind) {
        Label t = new Label(title);
        t.getStyleClass().add("title-md");
        Label m = new Label(message);
        m.getStyleClass().add("text-muted");
        m.setWrapText(true);
        VBox box = new VBox(4, t, m);
        box.getStyleClass().addAll("toast", "toast-" + kind);
        box.setMaxWidth(340);
        box.setOpacity(0);
        getChildren().add(0, box);
        TranslateTransition slide = new TranslateTransition(Duration.millis(260), box);
        slide.setFromX(60);
        slide.setToX(0);
        FadeTransition in = new FadeTransition(Duration.millis(260), box);
        in.setToValue(1);
        PauseTransition stay = new PauseTransition(Duration.seconds(5));
        FadeTransition out = new FadeTransition(Duration.millis(400), box);
        out.setToValue(0);
        out.setOnFinished(e -> getChildren().remove(box));
        new SequentialTransition(new javafx.animation.ParallelTransition(slide, in), stay, out).play();
    }
}
