package com.filebridge.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

public class FileBridgeApplication extends Application {

    private ConfigurableApplicationContext springContext;

    @Override
    public void init() {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(SpringConfig.class)
                .web(WebApplicationType.SERVLET)
                .headless(false);

        // Load ~/.filebridge/settings.properties as an additional property source so that
        // any port the user saved previously overrides application.properties on this launch.
        Path userSettings = Path.of(System.getProperty("user.home"), ".filebridge", "settings.properties");
        if (Files.exists(userSettings)) {
            builder.properties("spring.config.additional-location=optional:file:" + userSettings.toAbsolutePath());
        }

        springContext = builder.run(getParameters().getRaw().toArray(new String[0]));
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        loader.setControllerFactory(springContext::getBean);

        Parent root = loader.load();
        Scene scene = new Scene(root, 1100, 720);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());

        stage.setTitle("JSR");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.show();
    }

    @Override
    public void stop() {
        if (springContext != null) springContext.close();
        Platform.exit();
        System.exit(0);
    }
}
