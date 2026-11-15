package com.nexus.banking.client.ui;

import com.nexus.banking.client.core.BankingClientCore;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** What every screen needs: the core, the model, toasts, modal overlays and navigation. */
final class UiContext {
    final BankingClientCore core;
    final ClientModel model;
    final StackPane host;
    final Toasts toasts = new Toasts();
    final Consumer<String> navigate;
    private final Deque<StackPane> overlays = new ArrayDeque<>();

    UiContext(BankingClientCore core, ClientModel model, StackPane host, Consumer<String> navigate) {
        this.core = core;
        this.model = model;
        this.host = host;
        this.navigate = navigate;
    }

    /** Shows {@code card} centred over a dimmed backdrop; returns the closer. */
    Runnable overlay(Node card) {
        StackPane dim = new StackPane(card);
        dim.getStyleClass().add("overlay");
        dim.setPickOnBounds(true);
        host.getChildren().add(dim);
        overlays.push(dim);
        return () -> {
            host.getChildren().remove(dim);
            overlays.remove(dim);
        };
    }

    void closeAllOverlays() {
        for (StackPane p : overlays) host.getChildren().remove(p);
        overlays.clear();
    }
}
