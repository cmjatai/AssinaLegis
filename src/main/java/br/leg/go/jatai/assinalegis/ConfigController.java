package br.leg.go.jatai.assinalegis;

import javafx.fxml.FXML;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
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

    @FXML
    private PasswordField certPasswordField;

    @FXML
    private ColorPicker bgColorPicker;

    @FXML
    private ColorPicker signatureNameColorPicker;

    @FXML
    private ColorPicker signatureDateColorPicker;

    private ConfigService configService;
    private Stage dialogStage;

    public void initialize() {
        configService = ConfigService.getInstance();
        loadConfig();
    }
    public void setConfigService(ConfigService configService) {
        this.configService = configService;
    }

    @FXML
    private void loadConfig() {
        urlField.setText(configService.getUrl());
        tokenField.setText(configService.getToken());
        certPathField.setText(configService.getCertPath());
        certPasswordField.setText(configService.getCertPassword());
        bgColorPicker.setValue(Color.web(configService.getSignatureBgColor()));
        signatureNameColorPicker.setValue(Color.web(configService.getSignatureNameColor()));
        signatureDateColorPicker.setValue(Color.web(configService.getSignatureDateColor()));
    }

    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;
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
        configService.setCertPassword(certPasswordField.getText());
        configService.setSignatureBgColor(toHexString(bgColorPicker.getValue()));
        configService.setSignatureNameColor(toHexString(signatureNameColorPicker.getValue()));
        configService.setSignatureDateColor(toHexString(signatureDateColorPicker.getValue()));

        dialogStage.close();
    }

    private String toHexString(Color color) {
        return String.format( "#%02X%02X%02X",
            (int)( color.getRed() * 255 ),
            (int)( color.getGreen() * 255 ),
            (int)( color.getBlue() * 255 ) );
    }

    @FXML
    private void onCancel() {
        dialogStage.close();
    }
}
