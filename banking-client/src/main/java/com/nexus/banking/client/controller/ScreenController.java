package com.nexus.banking.client.controller;

/** A screen hosted inside the main shell. */
public interface ScreenController {
    /** Called each time the screen becomes visible. */
    default void onShow() {
    }

    /** Called when another screen replaces it. */
    default void onHide() {
    }
}
