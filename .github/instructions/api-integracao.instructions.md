---
applyTo: "src/main/java/**/ApiService.java,src/main/java/**/ConfigService.java"
---

# Integração com API no AssinaLegis

## Visão Geral

O AssinaLegis se comunica com um backend **Django REST Framework (DRF)** via HTTP/HTTPS. Toda comunicação passa por `ApiService` (Singleton), que usa **OkHttp 4.9.3** como cliente HTTP.

---

## Configuração da Conexão

| Parâmetro | Como configurar | Padrão |
|---|---|---|
| URL base | `ConfigService.setUrl(url)` | `""` (não configurada) |
| Token de autenticação | `ConfigService.setToken(token)` | `""` |
| Timeout de conexão | Fixo em `ApiService` | 30 segundos |
| Timeout de leitura | Fixo em `ApiService` | 30 segundos |
| Timeout de escrita | Fixo em `ApiService` | 30 segundos |

---

## Padrão de URL

```
{baseUrl}/api/{appLabel}/{modelName}/{id}/{action}/
```

Exemplos:
```
GET  http://servidor/api/base/casalegislativa/         → lista casas legislativas
GET  http://servidor/api/docs/documento/42/pdf/        → baixa PDF do documento 42
POST http://servidor/api/docs/documento/42/assinar/    → envia documento assinado
```

- Barra final (`/`) é sempre adicionada automaticamente (exceto no endpoint `/api/token`).
- Parâmetros de query são URL-encoded via `URLEncoder.encode(…, StandardCharsets.UTF_8)`.

---

## Autenticação

Usa **DRF Token Authentication**:

```
Authorization: Token <token>
```

O token é obtido via endpoint de login e configurado pelo usuário na tela de Configurações. Não use sessão nem Basic Auth.

---

## Métodos do ApiService

Todos os métodos retornam `InputStream` para suportar respostas grandes (PDFs):

```java
// Leitura simples
InputStream get(String appLabel, String modelName, Integer id, String action,
                Map<String, Object> params) throws Exception;

// Escrita (corpo como Map ou POJO)
InputStream post(String appLabel, String modelName, Integer id, String action,
                 Object form, Map<String, Object> params) throws Exception;

InputStream put(String appLabel, String modelName, Integer id, String action,
                Object form, Map<String, Object> params) throws Exception;

InputStream patch(String appLabel, String modelName, Integer id, String action,
                  Object form, Map<String, Object> params) throws Exception;
```

- `appLabel` e `modelName` = rótulo do app e nome do modelo Django (minúsculos).
- `id` pode ser `null` para endpoints de lista.
- `action` pode ser `null` para endpoints de CRUD padrão.

---

## Formato do Corpo da Requisição

### JSON (`application/json`)

Passe um **POJO** ou **`Map<String, Object>`** sem arquivos:
```java
Map<String, Object> body = Map.of("status", "assinado", "observacao", "OK");
InputStream resp = ApiService.getInstance().patch("docs", "documento", 42, null, body, null);
```

O `ApiService` serializa automaticamente com Jackson.

### Multipart (`multipart/form-data`)

Passe um `Map<String, Object>` contendo ao menos um valor do tipo `InputStream`, `File`, `byte[]` ou `FileData`:

```java
Map<String, Object> form = new HashMap<>();
form.put("arquivo", new ByteArrayInputStream(pdfBytes));
form.put("nome_arquivo", "documento_assinado.pdf");
InputStream resp = ApiService.getInstance().post("docs", "documento", 42, "assinar", form, null);
```

O `ApiService` detecta automaticamente se o body deve ser multipart ao verificar os tipos dos valores.

### FileData

`FileData` é uma classe interna de `ApiService` para envios com nome e tipo de conteúdo explícitos:
```java
ApiService.FileData fd = new ApiService.FileData(bytes, "application/pdf", "documento.pdf");
form.put("arquivo", fd);
```

---

## Tratamento de Erros

- Qualquer resposta não-2xx lança `RuntimeException("API Error: {código} - {corpo}")`.
- O corpo da resposta de erro é lido completamente e incluído na mensagem.
- O `ResponseBody` de erro é sempre fechado no bloco `try-with-resources`.
- **Não** tente fazer retry automático — deixe o chamador decidir.

---

## Inicialização da Casa Legislativa

Ao configurar uma nova URL, `ConfigService.setUrl()` aciona `updateCasaLegislativa()` em background:

```java
private void updateCasaLegislativa() {
    new Thread(() -> {
        try {
            InputStream response = ApiService.getInstance()
                .get("base", "casalegislativa", null, null, null);
            JsonNode root = mapper.readTree(response);
            if (root.has("results") && root.get("results").isArray()) {
                JsonNode results = root.get("results");
                if (results.size() > 0) {
                    setCasaLegislativa(results.get(0));
                }
            }
        } catch (Exception e) {
            e.printStackTrace(); // Não propaga — falha silenciosa intencional no boot
        }
    }).start();
}
```

O `JsonNode` da casa legislativa é serializado para String e salvo em `Preferences`. Ao ler, é desserializado com `ObjectMapper.readValue(json, JsonNode.class)`.

---

## Listagem e Carregamento de Documentos

O `DocumentViewerController` carrega a lista de documentos pendentes de assinatura chamando:

```java
InputStream resp = ApiService.getInstance().get("docs", "documento", null, null,
    Map.of("status", "pendente"));
```

O PDF de cada documento é carregado sob demanda com:

```java
InputStream pdf = ApiService.getInstance().get("docs", "documento", item.getId(), "pdf", null);
```

---

## Boas Práticas

| Regra | Motivo |
|---|---|
| Sempre fechar o `InputStream` retornado | Libera a conexão OkHttp |
| Nunca chamar `ApiService` na JavaFX Application Thread | Bloqueia a UI |
| Validar `baseUrl` antes de chamar qualquer método | Evita `IllegalArgumentException` obscuro |
| Não logar tokens ou senhas nas mensagens de erro | Segurança |
| Usar `Map.of()` para params imutáveis (Java 9+) | Código mais limpo |
