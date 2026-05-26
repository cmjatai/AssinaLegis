# GitHub Copilot Instructions — AssinaLegis

## Visão Geral

**AssinaLegis** é um aplicativo desktop JavaFX para **assinatura digital de documentos PDF** segundo o padrão ICP-Brasil, desenvolvido pela **Câmara Municipal de Jataí** (GO).

- **groupId / pacote base:** `br.leg.go.jatai.assinalegis`
- **Java:** 21 (com Java Platform Module System — `module-info.java`)
- **Build:** Maven 3 — use `mvn package` para gerar o JAR distribuível
- **UI:** JavaFX 21 com FXML (layouts em `src/main/resources/…/assinalegis/*.fxml`)
- **Idioma do código:** comentários, mensagens de log e textos de UI em **português brasileiro**

---

## Regras Gerais para Sugestões

1. **Java 21** — use Records, Pattern Matching, Sealed Classes e outras features modernas quando agregar clareza.
2. **Sem frameworks de injeção de dependência** — serviços usam **Singleton** com `getInstance()` sincronizado.
3. **Sem bibliotecas de UI além de JavaFX** — não sugira Swing widgets standalone (exceto via `SwingFXUtils` para interop com PDFBox).
4. **Português** — nomes de variáveis, comentários e mensagens ao usuário devem ser em pt-BR quando o contexto for de apresentação.
5. **Segurança** — nunca armazene senhas/PINs em log, strings literais ou memória desnecessária; use `char[]` para credenciais.
6. **Thread safety na UI** — qualquer atualização de componente JavaFX deve ser feita via `Platform.runLater(…)`.
7. **Module system** — ao adicionar dependências, atualize `module-info.java` com os `requires` adequados.

---

## Arquitetura de Serviços

| Classe | Responsabilidade |
|---|---|
| `Launcher` | Ponto de entrada separado do `App` (contorna restrições de classpath JavaFX) |
| `App` | `javafx.application.Application` — bootstrap da janela principal |
| `ConfigService` | Singleton — persiste configurações via `java.util.prefs.Preferences`; implementa Observer pattern |
| `ApiService` | Singleton — cliente HTTP REST (OkHttp) para o backend Django |
| `TokenService` | Detecção de bibliotecas PKCS#11 e abertura de `KeyStore` para tokens A3 |
| `AssinaturaService` | Lógica de assinatura PDF (PDFBox + BouncyCastle + SunPKCS11) |

Mais detalhes em [`.github/instructions/arquitetura.instructions.md`](.github/instructions/arquitetura.instructions.md).

---

## UI / FXML

- Telas: `main.fxml` → `MainController`, `document_viewer.fxml` → `DocumentViewerController`, `config.fxml` → `ConfigController`.
- IDs de componentes FXML usam **camelCase** prefixado pela função: `btnAssinar`, `lblStatus`, `chkSelectAll`.
- Use `@FXML` para injeção; nunca acesse componentes de FXML em threads não-JavaFX.
- Ao criar novas telas, siga o padrão `fx:controller` + controller com `initialize()`.

Detalhes em [`.github/instructions/javafx-ui.instructions.md`](.github/instructions/javafx-ui.instructions.md).

---

## Assinatura Digital

- Padrão: **CAdES/PAdES PKCS#7 Detached** (`SUBFILTER_ADBE_PKCS7_DETACHED`) via PDFBox + BouncyCastle.
- Certificados A3 (token/smartcard): carregados via **SunPKCS11** com detecção automática de biblioteca.
- Certificados A1 (PFX/P12): carregados diretamente via `KeyStore.getInstance("PKCS12")`.
- Coordenadas da assinatura visível: o viewer roda a **200 DPI**; o PDFBox usa **72 DPI** — sempre aplique `scaleFactor = 72.0 / 200.0`.
- **Nunca logar PIN** do token; se o erro contiver `CKR_PIN_INCORRECT` / `CKR_PIN_LOCKED`, interrompa imediatamente para evitar bloqueio.

Detalhes em [`.github/instructions/assinatura-digital.instructions.md`](.github/instructions/assinatura-digital.instructions.md).

---

## Integração com API

- Backend: **Django REST Framework** na URL configurada pelo usuário.
- Autenticação: `Authorization: Token <token>` (DRF Token Auth).
- Padrão de URL: `{baseUrl}/api/{appLabel}/{modelName}/{id}/{action}/`
- Cliente: `ApiService` com métodos `get`, `post`, `put`, `patch` — todos retornam `InputStream`.
- Uploads de arquivo devem usar `multipart/form-data`; JSON simples usa `application/json`.

Detalhes em [`.github/instructions/api-integracao.instructions.md`](.github/instructions/api-integracao.instructions.md).

---

## Build e Empacotamento

- `mvn package` gera:
  - `target/assinalegis-<version>.jar` (JAR com manifest apontando para `libs/`)
  - `target/assinalegis-<version>-shaded.jar` (uber-JAR)
  - `target/libs/` (dependências separadas)
- Empacotamento Linux em `packaging/linux/` (scripts `postinst`/`prerm` para `.deb`).
- Instalação alvo: `/opt/assinalegis`; link simbólico em `/usr/bin/assinalegis`.
- O debug é controlado pela propriedade `app.debug.mode` no `pom.xml` (injetada em `application.properties`).

Detalhes em [`.github/instructions/build-packaging.instructions.md`](.github/instructions/build-packaging.instructions.md).

---

## Testes

- Framework: **JUnit Jupiter 5**.
- Testes em `src/test/java/br/leg/go/jatai/assinalegis/`.
- Execute com `mvn test`.
- Testes de lógica de assinatura devem usar PDFs e certificados de teste — nunca certificados reais.
