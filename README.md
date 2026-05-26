# AssinaLegis

<p align="center">
  <img src="src/main/resources/icon.png" alt="AssinaLegis" width="256"/>
</p>

> Aplicativo desktop para **assinatura digital de documentos PDF** segundo o padrão ICP-Brasil, desenvolvido pela [Câmara Municipal de Jataí-GO](https://www.jatai.go.leg.br) para o [SAPL/Interlegis](https://github.com/interlegis/sapl), para o Brasil.

![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![JavaFX](https://img.shields.io/badge/JavaFX-21-orange)
![Maven](https://img.shields.io/badge/build-Maven-red?logo=apachemaven)
![License](https://img.shields.io/badge/license-GPL--3.0-green)

O AssinaLegis permite ao servidor público visualizar e assinar documentos PDF com certificado A1 (arquivo PFX/P12) ou A3 (token/smartcard) em dois fluxos complementares: no modo **M1 (SAPL/API)**, os documentos são carregados da API e enviados automaticamente após a assinatura; no modo **M2 (Local)**, os arquivos PDF são carregados do computador, assinados por etapas e salvos em pasta local.

---

## Sumário

1. [Para Usuários Finais — Download e Instalação](#1-para-usuários-finais--download-e-instalação)
2. [Primeira Configuração](#2-primeira-configuração)
3. [Modos de Operação (M1 e M2)](#3-modos-de-operação-m1-e-m2)
4. [Funcionalidades](#4-funcionalidades)
5. [Para Desenvolvedores — Pré-requisitos](#5-para-desenvolvedores--pré-requisitos)
6. [Como Compilar e Executar](#6-como-compilar-e-executar)
7. [Gerando Instaladores Nativos](#7-gerando-instaladores-nativos)
8. [Estrutura do Projeto](#8-estrutura-do-projeto)
9. [Arquitetura e Tecnologias](#9-arquitetura-e-tecnologias)
10. [Detalhes Técnicos Avançados](#10-detalhes-técnicos-avançados)
11. [Licença](#11-licença)

---

## 1. Para Usuários Finais — Download e Instalação

Os instaladores prontos para uso incluem a runtime Java embutida — **não é necessário instalar o Java separadamente**. Por serem grandes (> 100 MB), não estão no repositório; estão disponíveis no Drive compartilhado abaixo:

> 📦 **[Download dos Instaladores — Google Drive](https://drive.google.com/drive/folders/1va1BkV7KyGoOuFIh8rsNMWclBkxA93Tt?usp=sharing)**

| Sistema | Arquivo | Como instalar |
|---|---|---|
| Linux (Ubuntu/Debian) | `assinalegis_*.deb` | `sudo dpkg -i assinalegis_*.deb` |
| Windows | `AssinaLegis-*.msi` | Executar o instalador e seguir os passos |
| jar | `AssinaLegis-*.jar` | Possuíndo Java 21 instalado, o jar executará |

Versão 1.2 instalável para Windows será disponibilizada em 28/05/2026.

Após a instalação, o AssinaLegis aparece no menu de aplicativos do sistema operacional.

---

## 2. Primeira Configuração

Na primeira execução, acesse **Arquivo → Configurações** e preencha:

| Campo | Descrição | Exemplo |
|---|---|---|
| **URL da API** | Endereço do backend Django | `https://sapl.jatai.go.leg.br` |
| **Token de Acesso** | Token DRF gerado na conta do usuário | `9944b09199c62bcf...` |
| **Certificado Digital** | Caminho para o arquivo `.pfx` ou `.p12` (certificado A1) | `/home/usuario/cert.pfx` |
| **Senha do Certificado** | Senha do arquivo PFX/P12 | — |

> **Tokens A3 (smartcard/e-Token):** não precisam de configuração de arquivo; basta conectar o dispositivo antes de assinar. O AssinaLegis detecta a biblioteca PKCS#11 automaticamente.

As configurações são salvas localmente de forma persistente (não é necessário reinserir a cada abertura).

---

## 3. Modos de Operação (M1 e M2)

O AssinaLegis possui dois modos de trabalho no visualizador de documentos:

- **M1 (SAPL/API):** busca documentos diretamente da API, exibe a lista de proposições e permite enviar os PDFs assinados de volta ao backend.
- **M2 (Local):** trabalha com arquivos PDF locais, sem depender da API para carregar documentos, ideal para assinatura por etapas e processamento manual.

### M1 (SAPL/API)

- Exibe botão de atualização da lista de documentos vindos da API.
- Carrega itens do tipo proposição/documento remoto.
- Permite assinar e submeter arquivos assinados para a API.
- Quando não há token configurado, o M1 fica indisponível.

### M2 (Local)

- Permite carregar PDFs locais em múltiplas etapas, sem limpar automaticamente a lista atual.
- Exibe botão para remover arquivos individualmente na lista.
- Exibe botão **Limpar Lista** para remover todos os arquivos locais carregados.
- Permite assinar documentos e salvar os arquivos assinados em pasta local.

### Quando usar cada modo

- Use **M1** quando o fluxo envolve documentos oficiais já disponíveis no SAPL e devolução automática para API.
- Use **M2** quando os arquivos já estão no computador e o objetivo é assinatura local com controle manual do lote.

### Passo a passo rápido

#### Fluxo M1 (SAPL/API)

1. Acesse **Configurações** e preencha **URL da API** e **Token de Acesso**.
2. Ative o modo **M1 (SAPL/API)**.
3. Clique em **Atualizar Lista** para carregar os documentos pendentes.
4. Selecione os documentos, posicione a marcação de assinatura no preview e clique em **Assinar Todos Selecionados**.
5. Clique em **Submeter Arquivos Assinados** para enviar os PDFs assinados para a API.

#### Fluxo M2 (Local)

1. Ative o modo **M2 (Local)**.
2. Clique em **Carregar PDFs** quantas vezes forem necessárias para montar o lote por etapas.
3. Use **Remover** em cada item para excluir arquivos específicos, quando necessário.
4. Use **Limpar Lista** para reiniciar o lote local.
5. Selecione os arquivos, posicione a marcação e clique em **Assinar Todos Selecionados**.
6. Clique em **Salvar Arquivos Assinados** para escolher a pasta de destino.

---

## 4. Funcionalidades

- **Busca automática de documentos** pendentes de assinatura via API REST
- **Visualização de PDF** integrada com zoom e navegação por páginas
- **Posicionamento visual da assinatura** — clique na página para definir onde a assinatura visível será inserida
- **Assinatura digital padrão ICP-Brasil** (PAdES — PKCS#7 Detached)
- **Certificados A1** — arquivos `.pfx` / `.p12`
- **Certificados A3** — tokens USB e smartcards (SafeSign, eToken, Gemalto, Epad, WatchData)
- **Envio automático** do PDF assinado de volta à API
- **Assinatura em lote** — múltiplos documentos de uma só vez
- **Aparência personalizável** da assinatura visível (cores de fundo, nome e data)

---

## 5. Para Desenvolvedores — Pré-requisitos

| Ferramenta | Versão mínima | Verificar |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9 | `mvn -version` |
| Git | qualquer | `git --version` |

> O JDK deve incluir o módulo `jdk.crypto.cryptoki` (presente por padrão nos JDKs 21 da Adoptium, Oracle e OpenJDK). Ele é necessário para o suporte a tokens A3 via SunPKCS11.

Para **gerar instaladores nativos** (perfis `deb` e `windows`) são necessários, respectivamente:
- Linux: `dpkg-deb` e `fakeroot` — `sudo apt install dpkg fakeroot`
- Windows: [WiX Toolset v3+](https://wixtoolset.org/)

---

## 6. Como Compilar e Executar

### Clonar o repositório

```bash
git clone https://github.com/camara-jatai/assinalegis.git
cd assinalegis
```

### Executar em modo desenvolvimento (sem gerar JAR)

```bash
mvn javafx:run
```

### Compilar e gerar o JAR executável

```bash
mvn clean package
```

Artefatos gerados:

| Arquivo | Descrição |
|---|---|
| `dist/assinalegis-<versão>.jar` | JAR executável (uber-JAR com todas as dependências) |
| `target/assinalegis-<versão>.jar` | JAR leve com manifest apontando para `target/libs/` |
| `target/libs/*.jar` | Dependências separadas |

Executar o JAR gerado:

```bash
java -jar dist/assinalegis-1.2.0-SNAPSHOT.jar
```

### Executar os testes

```bash
mvn test
```

### Ativar modo debug

```bash
mvn javafx:run -Dapp.debug=true
```

---

## 7. Gerando Instaladores Nativos

O projeto usa o plugin `jpackage-maven-plugin` para empacotar o aplicativo com uma runtime Java embutida. Os instaladores são salvos em `dist/`.

### Linux — pacote `.deb`

```bash
mvn clean package -Pdeb
```

Gera: `dist/AssinaLegis-<versão>.deb`

O pacote `.deb` instala o aplicativo em `/opt/assinalegis/`, cria entrada no menu de aplicativos e link simbólico em `/usr/bin/assinalegis`.

### Windows — instalador `.msi`

Execute em um ambiente Windows com o WiX Toolset instalado:

```bash
mvn clean package -Pwindows
```

Gera: `dist/AssinaLegis-<versão>.msi`

> **Por que os instaladores não estão no repositório?**
> O `jpackage` embute uma runtime Java completa no instalador, resultando em arquivos de 120–200 MB. Para evitar poluir o histórico Git, os instaladores prontos são distribuídos via Google Drive (veja a [seção 1](#1-para-usuários-finais--download-e-instalação)).

---

## 8. Estrutura do Projeto

```
assinalegis/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   ├── module-info.java                  ← declaração do módulo JPMS
│   │   │   └── br/leg/go/jatai/assinalegis/
│   │   │       ├── Launcher.java                 ← ponto de entrada
│   │   │       ├── App.java                      ← bootstrap JavaFX
│   │   │       ├── MainController.java           ← controller da janela principal
│   │   │       ├── DocumentViewerController.java ← viewer PDF + seleção de assinatura
│   │   │       ├── ConfigController.java         ← diálogo de configurações
│   │   │       ├── ConfigService.java            ← persistência de configurações
│   │   │       ├── ApiService.java               ← cliente HTTP REST
│   │   │       ├── TokenService.java             ← detecção de tokens PKCS#11
│   │   │       └── AssinaturaService.java        ← lógica de assinatura digital
│   │   └── resources/
│   │       ├── application.properties            ← versão e flags (gerado pelo Maven)
│   │       ├── icon.png                          ← ícone da aplicação
│   │       └── br/leg/go/jatai/assinalegis/
│   │           ├── main.fxml                     ← layout da janela principal
│   │           ├── document_viewer.fxml          ← layout do viewer de PDF
│   │           ├── config.fxml                   ← layout das configurações
│   │           └── styles.css                    ← estilos globais da UI
│   └── test/
│       └── java/...                              ← testes JUnit 5
├── packaging/
│   └── linux/
│       ├── postinst                              ← script pós-instalação .deb
│       └── prerm                                ← script pré-remoção .deb
├── dist/                                         ← artefatos gerados (gitignored)
├── pom.xml
└── .github/
    ├── copilot-instructions.md                  ← instruções para o GitHub Copilot
    └── instructions/                            ← instruções detalhadas por área
```

---

## 9. Arquitetura e Tecnologias

### Stack tecnológico

| Camada | Tecnologia | Versão |
|---|---|---|
| Linguagem | Java | 21 |
| UI | JavaFX + FXML | 21.0.2 |
| PDF | Apache PDFBox | 3.0.1 |
| Criptografia | Bouncy Castle | 1.77 |
| HTTP Client | OkHttp | 4.9.3 |
| JSON | Jackson Databind | 2.16.1 |
| Testes | JUnit Jupiter | 5.10.2 |
| Build | Maven | 3.9+ |

### Camadas da aplicação

```
┌──────────────────────────────────────────────┐
│              Camada de UI (JavaFX)            │
│   MainController  DocumentViewerController   │
│   ConfigController                           │
├──────────────────────────────────────────────┤
│            Camada de Serviços                 │
│   ConfigService    ApiService                │
│   TokenService     AssinaturaService         │
├──────────────────────────────────────────────┤
│        Bibliotecas / Infraestrutura           │
│   PDFBox   BouncyCastle   OkHttp   PKCS#11   │
└──────────────────────────────────────────────┘
```

### Responsabilidade de cada serviço

| Classe | Padrão | Responsabilidade |
|---|---|---|
| `ConfigService` | Singleton + Observer | Persiste configurações via `java.util.prefs.Preferences`; notifica observers ao alterar |
| `ApiService` | Singleton | Cliente OkHttp para o backend Django REST; detecta multipart automaticamente |
| `TokenService` | Singleton | Detecta bibliotecas PKCS#11 do sistema; abre `KeyStore` de tokens A3 |
| `AssinaturaService` | Stateless | Assina PDFs com PDFBox + BouncyCastle; gera assinatura visível |

### Fluxo de assinatura

```
Usuário seleciona documentos
        │
        ▼
Clica na posição no PDF viewer  ──►  savedRect + savedPageIndex gravados no DocumentItem
        │
        ▼
Clica "Assinar"  ──►  escolhe certificado (A1 ou A3)  ──►  informa PIN/senha
        │
        ▼
AssinaturaService.assinarDocumentos()
    ├── Carrega originalBytes do PDF
    ├── Cria PDSignature (PAdES / PKCS#7 Detached)
    ├── Gera imagem da assinatura visível (bitmap com nome + data)
    ├── Assina com CMSSignedDataGenerator (BouncyCastle)
    └── Envia PDF assinado para a API via ApiService
```

---

## 10. Detalhes Técnicos Avançados

Esta seção destina-se a desenvolvedores que precisam contribuir com ou manter o código.

### Java Platform Module System (JPMS)

O projeto usa `module-info.java`. Ao adicionar dependências, obtenha o nome do módulo com:

```bash
jar --describe-module target/libs/<nova-dependencia>.jar
```

Em seguida, adicione `requires <nome.do.modulo>;` ao `module-info.java`.

### Certificados A3 e PKCS#11

O `TokenService` testa automaticamente os caminhos de bibliotecas PKCS#11 conhecidos (SafeSign, eToken, Gemalto, Epad, WatchData) em Linux e Windows. Ao detectar erro de PIN (`CKR_PIN_INCORRECT` / `CKR_PIN_LOCKED`), a execução é **interrompida imediatamente** para evitar o bloqueio permanente do token.

**Nunca inclua PINs ou senhas em logs**, mesmo em modo debug.

### Coordenadas de assinatura visível

O viewer renderiza PDFs a **200 DPI**; o PDFBox opera a **72 DPI**. A conversão é obrigatória:

```java
double scaleFactor = 72.0 / 200.0;
float pdfX = (float)(viewerX * scaleFactor);
// Inversão do eixo Y (JavaFX: top-left → PDF: bottom-left)
float pdfY = mediaBox.getHeight() - (float)(viewerY * scaleFactor) - pdfHeight;
```

### Padrão de URL da API

```
{baseUrl}/api/{appLabel}/{modelName}/{id}/{action}/
```

Exemplos:
```
GET  /api/base/casalegislativa/          → busca dados da casa legislativa
GET  /api/docs/documento/42/pdf/         → baixa PDF do documento 42
POST /api/docs/documento/42/assinar/     → envia PDF assinado
```

### Modo debug

Controlado pela propriedade `app.debug.mode` no `pom.xml` (padrão: `false`). Para habilitar sem alterar o POM:

```bash
mvn javafx:run -Dapp.debug=true
```

### Instruções para o GitHub Copilot

O diretório `.github/instructions/` contém arquivos de instrução detalhados que o GitHub Copilot carrega automaticamente ao trabalhar em cada área do código:

| Arquivo | Quando é carregado |
|---|---|
| `arquitetura.instructions.md` | Qualquer arquivo `.java` |
| `javafx-ui.instructions.md` | Controllers, FXMLs, CSS |
| `assinatura-digital.instructions.md` | `AssinaturaService`, `TokenService` |
| `api-integracao.instructions.md` | `ApiService`, `ConfigService` |
| `build-packaging.instructions.md` | `pom.xml`, `packaging/`, `module-info.java` |

---

## 11. Licença

Este projeto está licenciado sob a **GNU General Public License v3.0 (GPL-3.0)**.
Consulte o arquivo [`LICENSE`](LICENSE) para mais detalhes.

---

<p align="center">
  Desenvolvido pela <strong>Câmara Municipal de Jataí</strong> — GO
</p>
