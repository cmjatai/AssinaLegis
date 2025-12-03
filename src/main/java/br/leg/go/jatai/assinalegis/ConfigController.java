package br.leg.go.jatai.assinalegis;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;

public class ConfigController {

    @FXML
    private TextField urlField;

    @FXML
    private TextField tokenField;

    @FXML
    private TextField certPathField;

    private ConfigService configService;
    private Stage dialogStage;

    @FXML
    public void initialize() {
        configService = new ConfigService();
        loadConfig();
    }

    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;
    }

    private void loadConfig() {
        urlField.setText(configService.getUrl());
        tokenField.setText(configService.getToken());
        certPathField.setText(configService.getCertPath());
    }

    @FXML
    private void onBrowseCert() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Selecionar Certificado Digital");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Certificado PKCS#12", "*.pfx", "*.p12"));

        // Tenta abrir no diretório atual do arquivo configurado, se existir
        String currentPath = certPathField.getText();
        if (currentPath != null && !currentPath.isEmpty()) {
            File currentFile = new File(currentPath);
            if (currentFile.getParentFile() != null && currentFile.getParentFile().exists()) {
                fileChooser.setInitialDirectory(currentFile.getParentFile());
            }
        }

        File selectedFile = fileChooser.showOpenDialog(dialogStage);
        if (selectedFile != null) {
            certPathField.setText(selectedFile.getAbsolutePath());
        }
    }

    @FXML
    private void onSave() {
        configService.setUrl(urlField.getText());
        configService.setToken(tokenField.getText());
        configService.setCertPath(certPathField.getText());

        dialogStage.close();
    }

    @FXML
    private void onCancel() {
        dialogStage.close();
    }
}
