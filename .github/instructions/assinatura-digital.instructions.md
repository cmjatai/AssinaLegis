---
applyTo: "src/main/java/**/AssinaturaService.java,src/main/java/**/TokenService.java"
---

# Assinatura Digital no AssinaLegis

## Padrão de Assinatura

O AssinaLegis implementa assinatura no padrão **PAdES / CAdES-BES** usando:
- **PDFBox 3.x** — manipulação do PDF e embedding da assinatura
- **BouncyCastle 1.77** — geração do envelope PKCS#7/CMS
- **SunPKCS11** — acesso a tokens A3 (smartcard / e-Token)

Filtro usado:
```
/Filter /Adobe.PPKLite
/SubFilter /adbe.pkcs7.detached
```

---

## Fluxo de Assinatura

```
1. Usuário seleciona documentos na lista
2. Usuário clica na posição desejada no PDF viewer (define savedRect + savedPageIndex)
3. Usuário clica "Assinar"
4. DocumentViewerController abre diálogo para escolha de certificado (A1 ou A3)
5. AssinaturaService.assinarDocumentos(itens, keyStore, alias, senha) é chamado
6. Para cada documento:
   a. Carrega originalBytes no PDDocument
   b. Cria PDSignature com metadados
   c. Gera assinatura visível (imagem bitmap com nome e data)
   d. Assina com CMSSignedDataGenerator (BouncyCastle)
   e. Salva bytes assinados
   f. Envia bytes assinados para a API via ApiService
```

---

## Certificados A3 (Token / Smartcard)

### Detecção automática de bibliotecas PKCS#11

`TokenService.detectLibraries()` percorre caminhos predefinidos no sistema:

**Linux:**
- SafeSign: `/usr/lib/libaetpkss.so`, `/usr/lib64/libaetpkss.so`, `/usr/lib/x86_64-linux-gnu/libaetpkss.so`
- Gemalto: `/usr/lib/libgclib.so`, variantes
- eToken: `/usr/lib/libeToken.so`, `/usr/lib/libeTPkcs11.so`, variantes
- Epad: `/usr/lib/libepsng_p11.so`
- WatchData: `/usr/lib/libwdpkcs.so`

**Windows:**
- Caminhos em `c:/windows/system32/` — mesmos fornecedores

Para adicionar suporte a novo token, acrescente o caminho nas arrays `LINUX_LIBS` ou `WINDOWS_LIBS` em `TokenService`.

### Abertura do KeyStore

```java
String config = "--name=SmartCard\nlibrary=" + libraryPath;
Provider p = Security.getProvider("SunPKCS11");
p = p.configure(config);
Security.addProvider(p);
KeyStore ks = KeyStore.getInstance("PKCS11", p);
ks.load(null, pin);  // pin é char[]
```

- O prefixo `--` no config é obrigatório desde Java 9 (inline config).
- **Nunca logar o PIN** — nem em debug.
- Se `isPinError(e)` retornar `true`, lançar exceção imediatamente para **evitar bloquear o token** com tentativas repetidas.

### Detecção de erros de PIN

```java
private boolean isPinError(Throwable e) {
    while (e != null) {
        String msg = e.getMessage();
        if (msg != null && (
            msg.contains("CKR_PIN_INCORRECT") ||
            msg.contains("CKR_PIN_LOCKED") ||
            msg.contains("CKR_PIN_EXPIRED"))) {
            return true;
        }
        if (e instanceof FailedLoginException) return true;
        e = e.getCause();
    }
    return false;
}
```

---

## Certificados A1 (PFX / P12)

```java
KeyStore ks = KeyStore.getInstance("PKCS12");
try (InputStream fis = new FileInputStream(certPath)) {
    ks.load(fis, senha);
}
```

- Caminho e senha configurados pelo usuário em `ConfigService` (`KEY_CERT_PATH`, `KEY_CERT_PASSWORD`).
- Use `char[]` para a senha; limpe o array após o uso quando possível.

---

## Extração do CN do Certificado

```java
X509Certificate x509 = (X509Certificate) certChain[0];
String subjectDN = x509.getSubjectX500Principal().getName();
LdapName ln = new LdapName(subjectDN);
for (Rdn rdn : ln.getRdns()) {
    if (rdn.getType().equalsIgnoreCase("CN")) {
        nomeAssinante = rdn.getValue().toString();
        break;
    }
}
```

Requer `requires java.naming;` no `module-info.java`.

---

## Assinatura Visível

### Dimensões padrão

```java
float width  = (float)(5.0 / 2.54 * 72);   // 5 cm em pontos PDF (72 DPI)
float height = (float)(1.5 / 2.54 * 72);   // 1.5 cm em pontos PDF
```

### Conversão de coordenadas (Viewer → PDF)

```java
double scaleFactor = 72.0 / 200.0;  // viewer=200DPI, PDF=72DPI

float pdfX = (float)(viewerRect.getX() * scaleFactor);
float pdfWidth  = (float)(viewerRect.getWidth()  * scaleFactor);
float pdfHeight = (float)(viewerRect.getHeight() * scaleFactor);

// Inversão do eixo Y (JavaFX: top-left; PDF: bottom-left)
float pdfY = mediaBox.getHeight() - (float)(viewerRect.getY() * scaleFactor) - pdfHeight;
```

### Geração da imagem da assinatura

A assinatura visível é um bitmap (`BufferedImage`) gerado programaticamente com:
- Fundo colorido (`configService.getSignatureBgColor()`) — padrão `#003d71`
- Nome do signatário em branco (`#ffffff`)
- Data/hora em amarelo (`#ffff00`)
- Logomarca da casa legislativa (se disponível)

A imagem é convertida para `InputStream` e passada ao `PDVisibleSignDesigner`.

---

## Metadados da Assinatura

```java
PDSignature signature = new PDSignature();
signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
signature.setName("AssinaLegis");
signature.setLocation(/* nome da casa legislativa */);
signature.setReason("Assinatura Digital ICP-Brasil");
signature.setSignDate(Calendar.getInstance());
```

---

## Geração do CMS (BouncyCastle)

```java
ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
    .setProvider("BC")
    .build(privateKey);

CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
gen.addSignerInfoGenerator(
    new JcaSignerInfoGeneratorBuilder(
        new JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
    ).build(signer, (X509Certificate) certChain[0])
);
gen.addCertificates(new JcaCertStore(Arrays.asList(certChain)));

CMSTypedData msg = new CMSProcessableByteArray(contentToSign);
CMSSignedData signedData = gen.generate(msg, false); // detached = false aqui, embedding feito pelo PDFBox
return signedData.getEncoded();
```

---

## Múltiplas Assinaturas

Ao assinar múltiplos documentos, `AssinaturaService` **inverte a lista** antes de processar. Isso garante que a ordem de exibição na UI seja preservada após a API devolver os documentos assinados. Mantenha esse comportamento ao refatorar.

---

## Restrições e Boas Práticas

| Regra | Motivo |
|---|---|
| Nunca logar PIN ou senha | Evita vazamento em arquivos de log |
| Parar ao detectar `CKR_PIN_LOCKED` | Evitar bloqueio permanente do token |
| Usar `char[]` para credenciais | Permite limpeza da memória |
| Usar `originalBytes` e não o `PDDocument` aberto | Garante integridade da cadeia de assinaturas |
| Sempre fechar `PDDocument` em `try-with-resources` | Evita leak de file handles |
