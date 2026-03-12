package com.outlookautoemailier;

import com.outlookautoemailier.ui.SplashController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Objects;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        // ── Splash screen ────────────────────────────────────────────────────────
        FXMLLoader splashLoader = new FXMLLoader(
                Objects.requireNonNull(getClass().getResource("/fxml/Splash.fxml")));
        Parent splashRoot = splashLoader.load();
        SplashController splashCtrl = splashLoader.getController();

        Stage splashStage = new Stage(StageStyle.UNDECORATED);
        splashStage.setScene(new Scene(splashRoot, 900, 560));
        splashStage.setTitle("Outlook Auto Emailer");
        splashStage.show();

        // After splash closes, show the main window
        splashCtrl.startAndClose(() -> {
            splashStage.hide();
            try {
                showMainWindow(primaryStage);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private void showMainWindow(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(
                Objects.requireNonNull(getClass().getResource("/fxml/MainView.fxml")));
        Scene scene = new Scene(root, 1100, 700);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/css/style.css")).toExternalForm());
        stage.setTitle("Outlook Auto Emailer");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
