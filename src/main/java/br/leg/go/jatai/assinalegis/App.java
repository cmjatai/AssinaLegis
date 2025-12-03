package br.leg.go.jatai.assinalegis;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

/**
 * Aplicativo principal AssinaLegis.
 * Aplicativo desktop para assinatura digital - Câmara Municipal de Jataí.
 */
public class App extends Application implements ConfigService.ConfigObserver {

    private static Scene scene;
    private Stage stage;

    @Override
    public void start(Stage stage) throws IOException {
        this.stage = stage;
        ConfigService.getInstance().addObserver(this);

        scene = new Scene(loadFXML("main"), 800, 600);

        // Define título inicial
        stage.setTitle("AssinaLegis");
        JsonNode casa = ConfigService.getInstance().getCasaLegislativa(JsonNode.class);
        if (casa != null && casa.has("nome")) {
            stage.setTitle("AssinaLegis - " + casa.get("nome").asText());
        }

        // Configura o ícone da janela
        stage.getIcons().add(new Image(Objects.requireNonNull(App.class.getResourceAsStream("/icon.png"))));

        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void onConfigChanged(String key, Object newValue) {
        if (ConfigService.KEY_CASA.equals(key) && newValue instanceof JsonNode) {
            JsonNode casa = (JsonNode) newValue;
            if (casa.has("nome")) {
                String nome = casa.get("nome").asText();
                Platform.runLater(() -> {
                    if (stage != null) {
                        stage.setTitle("AssinaLegis - " + nome);
                    }
                });
            }
        }
    }

    static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }

    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(App.class.getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
