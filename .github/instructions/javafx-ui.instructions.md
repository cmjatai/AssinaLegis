---
applyTo: "src/main/java/**/*Controller.java,src/main/resources/**/*.fxml,src/main/resources/**/*.css"
---

# Convenções de UI / JavaFX no AssinaLegis

## Estrutura de Telas

| FXML | Controller | Propósito |
|---|---|---|
| `main.fxml` | `MainController` | Janela principal — menu + status bar + área de documentos |
| `document_viewer.fxml` | `DocumentViewerController` | Visualizador PDF + lista de documentos + botão assinar |
| `config.fxml` | `ConfigController` | Diálogo modal de configurações |

- O `document_viewer.fxml` é **incluído** dentro de `main.fxml` via `<fx:include>`.
- O `MainController` recebe a referência do `DocumentViewerController` por injeção FXML (`@FXML private DocumentViewerController documentViewerController`).

---

## Convenção de IDs de Componentes FXML

Use **camelCase** prefixado pela função do componente:

| Prefixo | Tipo | Exemplo |
|---|---|---|
| `btn` | Button | `btnAssinar`, `btnRefresh` |
| `lbl` | Label | `lblStatus`, `lblUserName` |
| `chk` | CheckBox | `chkSelectAll`, `chkDebug` |
| `txt` | TextField | `txtUrl`, `txtToken` |
| `pwd` | PasswordField | `pwdCertPassword`, `pwdPin` |
| `lst` | ListView | `lstDocumentos` |
| `cmb` | ComboBox | `cmbCertificados` |
| `tab` | Tab / TabPane | `tabConfiguracoes` |
| `pnl` | Pane / VBox / HBox | `pnlBotoes` |
| `cp` | ColorPicker | `cpCorFundo` |

---

## Ciclo de Vida dos Controllers

1. O `FXMLLoader` instancia o controller e injeta todos os campos `@FXML`.
2. O método `initialize()` é chamado automaticamente após a injeção — use-o para configurações iniciais.
3. **Nunca** acesse campos `@FXML` antes de `initialize()` nem em threads não-JavaFX.

```java
@FXML
public void initialize() {
    // Correto: configurações iniciais aqui
    configService = ConfigService.getInstance();
    btnAssinar.setDisable(true);
}
```

---

## Thread Safety na UI

Toda atualização de componente JavaFX deve ocorrer na **JavaFX Application Thread**:

```java
// Correto — chamado de thread de fundo
Platform.runLater(() -> {
    statusLabel.setText("Assinatura concluída");
    progressBar.setProgress(1.0);
});

// Errado — pode lançar IllegalStateException
new Thread(() -> {
    statusLabel.setText("Processando..."); // NUNCA faça isto
}).start();
```

Para tarefas longas (assinar documentos, chamadas de API), use:
- `new Thread(…).start()` para tarefas simples de fundo.
- `javafx.concurrent.Task<T>` para tarefas com progresso e callback seguro na UI.

---

## Janelas Modais

Para abrir um diálogo modal (como a tela de Configurações):

```java
FXMLLoader loader = new FXMLLoader(getClass().getResource("config.fxml"));
Parent root = loader.load();

Stage stage = new Stage();
stage.setTitle("Configurações");
stage.initModality(Modality.APPLICATION_MODAL);  // bloqueia janela pai
stage.setScene(new Scene(root));

// Carrega ícone
stage.getIcons().add(new Image(
    Objects.requireNonNull(App.class.getResourceAsStream("/icon.png"))
));

// Passa dados para o controller
ConfigController controller = loader.getController();
controller.setDialogStage(stage);

stage.showAndWait();  // aguarda fechamento
```

---

## Viewer de PDF (`DocumentViewerController`)

### DocumentItem

`DocumentItem` é um record/wrapper que representa um documento na lista:
- `originalBytes` — bytes do PDF original, usado para assinatura (preserva integridade).
- `pdDocument` — instância `PDDocument` aberta para renderização.
- `savedRect` — `Rectangle` JavaFX representando a área de assinatura selecionada pelo usuário.
- `savedPageIndex` — índice (0-based) da página onde a assinatura deve ser inserida.

### Escala do Viewer

O viewer renderiza PDFs a **200 DPI**. O PDFBox usa **72 DPI** internamente. Ao converter coordenadas:

```java
double scaleFactor = 72.0 / 200.0;
float pdfX = (float) (viewerX * scaleFactor);
float pdfY = (float) (viewerY * scaleFactor);
```

### Coordenadas PDF vs JavaFX

| Sistema | Origem | Eixo Y |
|---|---|---|
| JavaFX | Canto superior esquerdo | Aumenta para baixo |
| PDFBox (PDF) | Canto inferior esquerdo | Aumenta para cima |

Conversão de Y (considerando altura da página em pontos PDF):
```java
float pdfY = mediaBox.getHeight() - (float)(viewerY * scaleFactor) - pdfHeight;
```

### Zoom

- `zoomProperty` é um `DoubleProperty` que controla o zoom via `Scale` transform no `Group`.
- Botões de zoom (`btnZoomIn`, `btnZoomOut`, `btnFitWidth`, `btnFitHeight`) manipulam `zoomProperty`.
- Ctrl+Clique esquerdo = zoom in; Ctrl+Clique direito = zoom out.

---

## Estilos CSS

- Arquivo: `src/main/resources/…/assinalegis/styles.css`
- Referenciado via `scene.getStylesheets().add(...)` no `App`.
- Nomeie classes CSS com kebab-case: `.btn-assinar`, `.status-bar`, `.document-item`.
- Evite estilos inline no FXML; centralize em `styles.css`.

---

## Ícone da Aplicação

- Arquivo: `src/main/resources/icon.png`
- Carregado em `App.start()` e em todas as janelas secundárias.
- Use sempre `Objects.requireNonNull(App.class.getResourceAsStream("/icon.png"))` — falha explicitamente se o ícone não existir.
- Em telas secundárias, envolva em try/catch para não bloquear a abertura da janela:
  ```java
  try {
      stage.getIcons().add(new Image(
          Objects.requireNonNull(App.class.getResourceAsStream("/icon.png"))
      ));
  } catch (Exception ignored) {}
  ```

---

## Padrões de Feedback ao Usuário

- **Status bar:** `statusLabel` no `MainController` — mensagens curtas de estado.
- **Log:** `logArea` (TextArea) em janela separada (`logStage`) — mensagens detalhadas; use `logArea.appendText(msg + "\n")`.
- **Alertas:** use `Alert` JavaFX padrão com `Modality.APPLICATION_MODAL`.
- **Erros críticos:** `Alert.AlertType.ERROR` com mensagem em português e detalhes técnicos no log.

```java
Alert alert = new Alert(Alert.AlertType.ERROR);
alert.setTitle("Erro na Assinatura");
alert.setHeaderText("Não foi possível assinar o documento");
alert.setContentText(e.getMessage());
alert.showAndWait();
```
