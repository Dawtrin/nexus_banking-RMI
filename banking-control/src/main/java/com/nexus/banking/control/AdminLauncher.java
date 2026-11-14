package com.nexus.banking.control;

/** Plain main class so the app starts from a classpath (jar) without --module-path tricks. */
public final class AdminLauncher {
    private AdminLauncher() {
    }

    public static void main(String[] args) {
        AdminApplication.main(args);
    }
}
