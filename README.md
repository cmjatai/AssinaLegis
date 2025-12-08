# AssinaLegis

Aplicativo desktop para assinatura digital de documentos legislativos, desenvolvido pela Câmara Municipal de Jataí.

O sistema permite visualizar documentos PDF obtidos via API, assiná-los digitalmente utilizando certificados A1 (PKCS#12) e enviá-los de volta para o sistema de gestão legislativa.

## Funcionalidades

*   **Integração via API REST:** Busca e envio de documentos.
*   **Visualização de PDF:** Renderização de documentos PDF dentro da aplicação.
*   **Assinatura Digital:** Assinatura padrão PAdES utilizando Bouncy Castle.
*   **Gestão de Certificados:** Suporte a arquivos `.pfx` ou `.p12`.
*   **Interface Moderna:** Desenvolvido com JavaFX.

## Requisitos

*   **Java JDK 21** ou superior.
*   **Maven 3.9** ou superior.

## Tecnologias Utilizadas

*   **Linguagem:** Java 21
*   **Interface Gráfica:** JavaFX 21
*   **Gerenciamento de Dependências:** Maven
*   **HTTP Client:** OkHttp 4.9.3
*   **Manipulação de PDF:** Apache PDFBox 3.0
*   **Criptografia/Assinatura:** Bouncy Castle 1.77
*   **Processamento JSON:** Jackson 2.16

## Como Compilar e Executar

### 1. Compilar o projeto

```bash
mvn clean compile
```

### 2. Executar localmente (Desenvolvimento)

```bash
mvn javafx:run
```

### 3. Gerar JAR Executável

Para gerar um arquivo `.jar` contendo todas as dependências (Fat Jar):

```bash
mvn clean package
```

O arquivo será gerado na pasta `dist/` na raiz do projeto:
*   `dist/assinalegis-1.0.0-SNAPSHOT.jar`

Para executar o JAR gerado:

```bash
java -jar dist/assinalegis-1.0.0-SNAPSHOT.jar
```

## Gerando Instaladores Nativos

O projeto utiliza o `jpackage` para criar instaladores nativos. Os artefatos finais serão salvos na pasta `dist/`.

### Linux (.deb)

Requer `dpkg-deb` e `fakeroot` instalados.

```bash
mvn clean package -Pdeb
```
Gera: `dist/assinalegis_1.0.0-1_amd64.deb`

### Windows (.msi)

Requer o WiX Toolset (v3 ou v4) instalado no ambiente de build Windows.

```bash
mvn clean package -Pwindows
```
Gera: `dist/AssinaLegis-1.0.0.msi`

## Estrutura de Pastas

*   `src/main/java`: Código fonte Java.
*   `src/main/resources`: Recursos (FXML, CSS, Imagens, Properties).
*   `dist/`: Pasta de destino para os executáveis e instaladores gerados.
*   `packaging/`: Recursos específicos para empacotamento (scripts, ícones extras).

## Configuração

Ao iniciar pela primeira vez, acesse o menu de configurações para definir:
1.  **URL da API:** Endereço do backend.
2.  **Token de Acesso:** Token de autenticação.
3.  **Certificado Digital:** Caminho para o arquivo `.pfx` (opcional, pode ser selecionado na hora de assinar).

## Licença

Este projeto está licenciado sob a licença **GNU General Public License v3.0 (GPL-3.0)**.
Consulte o arquivo `LICENSE` para obter mais detalhes.

Desenvolvido pela Câmara Municipal de Jataí.
