package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import javafx.scene.Parent;

import java.util.List;

/** One screen of NEXUS CONTROL. */
public interface AdminPage {
    String title();

    Parent view();

    /** Called when the page becomes visible (load page-specific data here). */
    default void onShow() {
    }

    /** Called after every refresh with what each node answered. */
    default void onSnapshot(List<NodeSnapshot> snapshots) {
    }
}
