package com.nexus.banking.client.ui;

import javafx.scene.Parent;

/** One screen of the customer app. */
interface Page {
    String id();

    String title();

    String subtitle();

    Parent view();

    default void onShow() {
    }
}
