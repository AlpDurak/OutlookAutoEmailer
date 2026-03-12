package com.outlookautoemailier.ui;

import javafx.animation.FadeTransition;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.net.URL;
import java.util.ResourceBundle;

public class SplashController implements Initializable {

    @FXML private StackPane   root;
    @FXML private ImageView   splashImage;

    /** Called by Main after the splash stage is shown; fades out after 2.5 s then runs onDone. */
    public void startAndClose(Runnable onDone) {
        // Fit image to the actual stage size when it changes
        root.widthProperty().addListener((obs, o, w) -> splashImage.setFitWidth(w.doubleValue()));
        root.heightProperty().addListener((obs, o, h) -> splashImage.setFitHeight(h.doubleValue()));

        FadeTransition hold = new FadeTransition(Duration.seconds(2.0), root);
        hold.setFromValue(1.0);
        hold.setToValue(1.0);
        hold.setOnFinished(e -> {
            FadeTransition fadeOut = new FadeTransition(Duration.seconds(0.6), root);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(ev -> onDone.run());
            fadeOut.play();
        });
        hold.play();
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {}
}
