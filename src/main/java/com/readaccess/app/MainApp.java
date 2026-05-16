package com.readaccess.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/readaccess/app/MainView.fxml"));
        Scene scene = new Scene(loader.load(), 820, 640);
        scene.getStylesheets().add(getClass().getResource("/com/readaccess/app/styles.css").toExternalForm());

        stage.setTitle("ReadAccess Exam Importer");
        stage.setScene(scene);
        stage.setMinWidth(720);
        stage.setMinHeight(560);
        stage.setFullScreen(true);
        stage.setFullScreenExitHint("");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
