package com.nexus.banking.client.ui;

import com.nexus.banking.contract.dto.Money;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.math.BigDecimal;

/** A money label that counts up/down to its new value and flashes green (money in) or red (money out). */
final class AnimatedAmount extends Label {
    private final SimpleDoubleProperty shown = new SimpleDoubleProperty(0);
    private Timeline timeline;
    private BigDecimal current;
    private final PauseTransition unflash = new PauseTransition(Duration.millis(1100));

    AnimatedAmount() {
        getStyleClass().add("balance-big");
        shown.addListener((o, a, b) -> setText(Money.format(BigDecimal.valueOf(Math.round(b.doubleValue())))));
        unflash.setOnFinished(e -> getStyleClass().removeAll("balance-flash-up", "balance-flash-down"));
        setText("—");
    }

    BigDecimal value() {
        return current;
    }

    /** Sets the value; animates and flashes when it changes after the first display. */
    void set(BigDecimal value, boolean animate) {
        if (value == null) return;
        BigDecimal old = current;
        current = value;
        if (timeline != null) timeline.stop();
        if (old == null || !animate || old.compareTo(value) == 0) {
            shown.set(value.doubleValue());
            setText(Money.format(value));
            return;
        }
        getStyleClass().removeAll("balance-flash-up", "balance-flash-down");
        getStyleClass().add(value.compareTo(old) > 0 ? "balance-flash-up" : "balance-flash-down");
        unflash.playFromStart();
        timeline = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(shown, old.doubleValue())),
                new KeyFrame(Duration.millis(750), new KeyValue(shown, value.doubleValue(), javafx.animation.Interpolator.EASE_BOTH)));
        timeline.setOnFinished(e -> setText(Money.format(value)));
        timeline.play();
    }
}
