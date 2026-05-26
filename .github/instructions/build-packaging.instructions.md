---
applyTo: "pom.xml,packaging/**,module-info.java"
---

# Build e Empacotamento do AssinaLegis

## Requisitos de Ambiente

| Ferramenta | Versão mínima |
|---|---|
| Java (JDK) | 21 |
| Maven | 3.9+ |
| Módulo JDK `jdk.crypto.cryptoki` | Incluído no JDK 21 por padrão |

---

## Comandos Principais

```bash
# Compilar e empacotar (gera os JARs e copia dependências)
mvn package

# Executar em desenvolvimento (com JavaFX plugin)
mvn javafx:run

# Executar testes unitários
mvn test

# Limpar artefatos anteriores
mvn clean

# Build completo limpo
mvn clean package
```

---

## Artefatos Gerados por `mvn package`

| Artefato | Localização | Descrição |
|---|---|---|
| JAR principal | `target/assinalegis-<version>.jar` | JAR executável com manifest apontando para `libs/` |
| Uber-JAR (shaded) | `target/assinalegis-<version>-shaded.jar` | JAR com todas as dependências embutidas |
| Dependências | `target/libs/*.jar` | Bibliotecas separadas para distribuição |

O manifest do JAR principal define:
- `Main-Class: br.leg.go.jatai.assinalegis.Launcher`
- `Class-Path: libs/<dep1>.jar libs/<dep2>.jar …`

---

## Propriedades do pom.xml

| Propriedade | Valor padrão | Descrição |
|---|---|---|
| `javafx.version` | `21.0.2` | Versão do JavaFX |
| `junit.version` | `5.10.2` | Versão do JUnit Jupiter |
| `main.class` | `br.leg.go.jatai.assinalegis.Launcher` | Classe de entrada |
| `app.name` | `AssinaLegis` | Nome exibido na janela |
| `app.debug.mode` | `false` | Ativa modo debug (`true` para desenvolvimento) |

Para ativar o modo debug localmente sem alterar o `pom.xml`:
```bash
mvn package -Dapp.debug.mode=true
# ou ao executar:
mvn javafx:run -Dapp.debug=true
```

A propriedade `app.debug.mode` é injetada em `application.properties` via Maven Resource Filtering:
```properties
app.debug=${app.debug.mode}
```

---

## Dependências Principais

| Biblioteca | Versão | Uso |
|---|---|---|
| `javafx-controls` | 21.0.2 | Componentes UI |
| `javafx-fxml` | 21.0.2 | Carregamento de FXML |
| `javafx-swing` | 21.0.2 | `SwingFXUtils` para renderizar PDFs |
| `pdfbox` | 3.0.1 | Leitura, renderização e assinatura de PDFs |
| `bcprov-jdk18on` | 1.77 | Criptografia BouncyCastle (provider) |
| `bcpkix-jdk18on` | 1.77 | PKI BouncyCastle (PKCS#7 / CMS) |
| `jackson-databind` | 2.16.1 | Serialização/desserialização JSON |
| `okhttp` | 4.9.3 | Cliente HTTP para API REST |
| `junit-jupiter-api` | 5.10.2 | Testes unitários (escopo `test`) |

> **Ao atualizar dependências:** verifique se o nome do módulo JPMS mudou e atualize `module-info.java` de acordo. Use `jar --describe-module <arquivo.jar>` para descobrir o nome do módulo.

---

## Adicionando Novas Dependências

1. Adicione a dependência no `pom.xml`.
2. Descubra o nome do módulo: `jar --describe-module target/libs/<nova-dep>.jar`
3. Adicione `requires <nome.do.modulo>;` no `module-info.java`.
4. Se a dependência não for modular (sem `module-info`), use `requires <nome-automatico>;` (baseado no nome do JAR, sem versão e com hifens substituídos por pontos).

---

## Maven Shade Plugin — Uber-JAR

O Shade Plugin exclui assinaturas digitais das dependências para evitar `SecurityException`:
```xml
<excludes>
    <exclude>META-INF/*.SF</exclude>
    <exclude>META-INF/*.DSA</exclude>
    <exclude>META-INF/*.RSA</exclude>
</excludes>
```

O uber-JAR é marcado como `shadedArtifactAttached=true` com classifier `shaded` — não substitui o JAR principal.

---

## Empacotamento para Linux (.deb)

Os scripts de empacotamento ficam em `packaging/linux/`:

| Script | Descrição |
|---|---|
| `postinst` | Executado após instalação do `.deb` |
| `prerm` | Executado antes da desinstalação |

### O que o `postinst` faz

1. Cria link simbólico `/usr/bin/assinalegis → /opt/assinalegis/bin/AssinaLegis`
2. Instala o arquivo `.desktop` em `/usr/share/applications/`
3. Adiciona `Keywords=assinador;assinatura;digital;AssinaLegis;jatai;`
4. Copia ícone para `/usr/share/pixmaps/assinalegis.png`
5. Adiciona `StartupWMClass=br.leg.go.jatai.assinalegis.App` para agrupar janelas no Dock
6. Executa `update-desktop-database`

### Estrutura de instalação alvo

```
/opt/assinalegis/
├── bin/
│   └── AssinaLegis          ← script de lançamento (java -jar ...)
├── lib/
│   ├── assinalegis-<ver>.jar
│   ├── assinalegis-AssinaLegis.desktop
│   ├── AssinaLegis.png
│   └── libs/
│       └── *.jar            ← dependências

/usr/bin/assinalegis          ← link simbólico
/usr/share/applications/assinalegis-AssinaLegis.desktop
/usr/share/pixmaps/assinalegis.png
```

---

## Executando Localmente (sem instalar)

```bash
# Após mvn package
cd target
java -jar assinalegis-<version>.jar
```

Ou via Maven:
```bash
mvn javafx:run
```

Para habilitar debug verbose do JavaFX:
```bash
mvn javafx:run -Dprism.verbose=true
```

---

## application.properties

Localizado em `src/main/resources/application.properties`. É processado pelo Maven Resource Filtering antes de ser copiado para `target/classes/`:

```properties
app.name=${project.name}
app.version=${project.version}
app.description=${project.description}
app.debug=${app.debug.mode}
```

Carregado em `App.loadAppProperties()` e `ConfigService.loadDebugMode()` via `App.class.getResourceAsStream("/application.properties")`.
