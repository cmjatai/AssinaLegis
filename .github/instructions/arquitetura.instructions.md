---
applyTo: "src/main/java/**/*.java"
---

# Arquitetura do AssinaLegis

## Visão Geral da Arquitetura

O AssinaLegis segue uma arquitetura em camadas baseada em **Singletons de Serviço** com UI desacoplada via **FXML/Controller**. Não há framework de DI (Guice, Spring, etc.) — toda injeção de dependência é feita via `getInstance()` nos controllers.

```
┌─────────────────────────────────────────┐
│           Camada de UI (JavaFX)          │
│  MainController  DocumentViewerController│
│  ConfigController                        │
├─────────────────────────────────────────┤
│           Camada de Serviços             │
│  ConfigService  ApiService              │
│  TokenService   AssinaturaService       │
├─────────────────────────────────────────┤
│       Bibliotecas / Infraestrutura       │
│  PDFBox  BouncyCastle  OkHttp  PKCS#11  │
└─────────────────────────────────────────┘
```

---

## Classes de Entrada

### `Launcher`
- Ponto de entrada real (`main()`).
- Existe separado de `App` para contornar a restrição do classloader do JavaFX: se `main` estiver na classe que estende `Application`, o JAR executável falha em ambientes sem JavaFX no class-path do boot.
- **Nunca mova** `main()` para dentro de `App`.

```java
// Correto
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
```

### `App`
- Estende `javafx.application.Application`.
- Implementa `ConfigService.ConfigObserver` para reagir a mudanças de configuração e atualizar o título da janela.
- Carrega `main.fxml` como cena raiz.
- Lê `app.name` e `app.version` de `application.properties` para compor o título.

---

## Padrão Singleton para Serviços

Todos os serviços seguem este template:

```java
public class XyzService {
    private static XyzService instance;

    private XyzService() {
        // inicialização privada
    }

    public static synchronized XyzService getInstance() {
        if (instance == null) {
            instance = new XyzService();
        }
        return instance;
    }
}
```

- **`synchronized`** é obrigatório no `getInstance()` para evitar race conditions na inicialização.
- Não use double-checked locking sem `volatile` — prefira a forma simples acima ou use um holder estático interno.

---

## ConfigService

- Persiste dados via `java.util.prefs.Preferences` (registro do sistema operacional / arquivo de preferências do usuário).
- Chaves públicas (`KEY_TOKEN`, `KEY_CASA`) ficam em constantes `public static final String`.
- Chaves internas ficam em `private static final String`.
- Implementa o **Observer Pattern**: controllers registram `ConfigObserver` para receber eventos de mudança.
- O método `updateCasaLegislativa()` dispara uma thread de fundo para consultar a API — **nunca bloqueia a thread JavaFX**.

### Chaves de Configuração

| Constante | Chave no Preferences | Descrição |
|---|---|---|
| `KEY_URL` | `url` | URL base do backend Django |
| `KEY_TOKEN` | `token` | Token de autenticação DRF |
| `KEY_CERT_PATH` | `cert_path` | Caminho do certificado A1 (PFX/P12) |
| `KEY_CERT_PASSWORD` | `cert_password` | Senha do certificado A1 |
| `KEY_CASA` | `casalegislativa` | JSON da casa legislativa |
| `KEY_SIGNATURE_BG_COLOR` | `signature_bg_color` | Cor de fundo da assinatura visível |
| `KEY_SIGNATURE_NAME_COLOR` | `signature_name_color` | Cor do nome na assinatura visível |
| `KEY_SIGNATURE_DATE_COLOR` | `signature_date_color` | Cor da data na assinatura visível |

> **Atenção de segurança:** `cert_password` é armazenada em texto plano via Preferences.
> Em futuras versões, considere criptografar com a senha do sistema operacional (p. ex. `javax.crypto` + chave derivada do login do OS).

---

## ApiService

- Cliente HTTP usando **OkHttp 4.9.3**.
- Timeouts padrão: 30 segundos para connect, read e write.
- Constrói URLs no formato: `{baseUrl}/api/{appLabel}/{modelName}/{id}/{action}/`
- Cabeçalho de autenticação: `Authorization: Token {token}`
- Retorna `InputStream` para permitir streaming de respostas grandes (PDFs).
- Uploads de arquivos usam `multipart/form-data` via `MultipartBody`; outros payloads usam `application/json`.
- Lança `RuntimeException` com o código HTTP em caso de erro (resposta não-2xx).

---

## TokenService

- Detecta automaticamente bibliotecas PKCS#11 no sistema operacional (Linux e Windows).
- Lista de caminhos candidatos em `LINUX_LIBS` e `WINDOWS_LIBS`.
- Tenta cada biblioteca em ordem, parando imediatamente se detectar erro de PIN (`CKR_PIN_INCORRECT`, `CKR_PIN_LOCKED`, `CKR_PIN_EXPIRED`).
- Usa `SunPKCS11` provider do JDK (módulo `jdk.crypto.cryptoki`).
- O `KeyStore` resultante é passado para `AssinaturaService` — **não mantém referência** ao PIN após o carregamento.

---

## AssinaturaService

- Recebe uma lista de `DocumentItem` (wrapper do `DocumentViewerController`).
- Extrai `PrivateKey` e `Certificate[]` do `KeyStore`.
- Para cada documento:
  1. Carrega os bytes originais do PDF.
  2. Cria `PDSignature` com filtro `FILTER_ADOBE_PPKLITE` / `SUBFILTER_ADBE_PKCS7_DETACHED`.
  3. Gera a assinatura visível com `PDVisibleSignDesigner` + `PDVisibleSigProperties`.
  4. Assina via `CMSSignedDataGenerator` (BouncyCastle).
  5. Envia o PDF assinado de volta para a API.
- A assinatura é sem estado — nenhum dado sensível é mantido entre chamadas.

---

## Module System (JPMS)

O arquivo `module-info.java` declara:

```java
module br.leg.go.jatai.assinalegis {
    requires javafx.controls;
    requires javafx.fxml;
    requires transitive javafx.graphics;
    requires javafx.base;
    requires javafx.swing;           // SwingFXUtils para renderizar PDFs
    requires java.desktop;           // AWT/BufferedImage
    requires transitive org.apache.pdfbox;
    requires org.apache.pdfbox.io;
    requires org.bouncycastle.provider;
    requires org.bouncycastle.pkix;
    requires java.prefs;             // Preferences API
    requires java.naming;            // LdapName para extrair CN do certificado
    requires java.net.http;
    requires transitive com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires okhttp3;
    requires okio;

    opens br.leg.go.jatai.assinalegis to javafx.fxml;
    exports br.leg.go.jatai.assinalegis;
}
```

- `opens … to javafx.fxml` é necessário para que o `FXMLLoader` acesse campos `@FXML` por reflexão.
- Ao adicionar novas dependências, sempre verifique o nome do módulo no MANIFEST da dependência ou use `jar --describe-module`.
