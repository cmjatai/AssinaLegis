# AssinaLegis

Aplicativo desktop para assinatura digital - Câmara Municipal de Jataí.

## Requisitos

- OpenJDK 21 ou superior
- Maven 3.9 ou superior

## Tecnologias

- Java 21
- JavaFX 21
- Maven

## Build

### Compilar o projeto

```bash
mvn clean compile
```

### Executar os testes

```bash
mvn test
```

### Criar JAR executável

```bash
mvn clean package
```

O JAR executável será gerado em `target/assinalegis-1.0.0-SNAPSHOT-shaded.jar`.

### Executar a aplicação

Com Maven:
```bash
mvn javafx:run
```

Ou com o JAR:
```bash
java -jar target/assinalegis-1.0.0-SNAPSHOT-shaded.jar
```

### Gerar instalador DEB (Debian/Ubuntu)

```bash
mvn clean package -Pdeb
```

O arquivo `.deb` será gerado em `target/dist/`.

**Nota:** A geração do pacote DEB requer que o comando `dpkg-deb` esteja disponível no sistema.

## Estrutura do Projeto

```
assinalegis/
├── pom.xml                                    # Configuração Maven
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   ├── module-info.java              # Definição do módulo Java
│   │   │   └── br/leg/go/jatai/assinalegis/
│   │   │       ├── App.java                  # Classe principal
│   │   │       └── MainController.java       # Controlador JavaFX
│   │   └── resources/
│   │       └── br/leg/go/jatai/assinalegis/
│   │           └── main.fxml                 # Interface JavaFX
│   └── test/
│       └── java/
│           └── br/leg/go/jatai/assinalegis/
│               └── AppTest.java              # Testes unitários
└── README.md
```

## Licença

MIT License - Câmara Municipal de Jataí
