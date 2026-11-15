package com.nexus.banking.client;

/** Plain main class so the app also starts from a classpath/shaded jar (JavaFX needs a non-Application entry point). */
public class Launcher {
    public static void main(String[] args) {
        BankingApp.main(args);
    }
}
