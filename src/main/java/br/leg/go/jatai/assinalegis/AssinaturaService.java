package br.leg.go.jatai.assinalegis;

import br.leg.go.jatai.assinalegis.DocumentViewerController.DocumentItem;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.scene.shape.Rectangle;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSigProperties;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSignDesigner;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.util.Matrix;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class AssinaturaService {

    /**
     * Assina uma lista de DocumentItems.
     *
     * @param itens    Lista de DocumentItems a serem assinados
     * @param keyStore KeyStore contendo o certificado e chave privada
     * @param alias    Alias do certificado no KeyStore
     * @param senha    Senha da chave privada
     * @throws Exception Em caso de erro na assinatura
     */
    public void assinarDocumentos(List<DocumentItem> itens, KeyStore keyStore, String alias, char[] senha) throws Exception {

        // 1. Recupera Chave Privada e Cadeia de Certificados
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, senha);
        Certificate[] certificateChain = keyStore.getCertificateChain(alias);

        if (privateKey == null || certificateChain == null) {
            throw new Exception("Chave privada ou cadeia de certificados não encontrada para o alias: " + alias);
        }

        for (DocumentItem item : itens) {
            byte[] originalBytes = item.getOriginalBytes();
            if (originalBytes == null) {
                // Fallback se não tiver bytes originais (ex: carregado via loadPdfPreview sem salvar no item)
                // Mas idealmente preloadPdf deve garantir isso.
                // Se não tiver, tentamos usar o PDDocument existente, mas isso pode quebrar assinaturas anteriores.
                PDDocument doc = item.getPdDocument();
                if (doc != null) {
                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        doc.save(baos);
                        originalBytes = baos.toByteArray();
                    }
                } else {
                    continue;
                }
            }

            try (PDDocument docToSign = Loader.loadPDF(originalBytes);
                 ByteArrayOutputStream baosSigned = new ByteArrayOutputStream()) {

                // 3. Cria a estrutura da assinatura no PDF
                PDSignature signature = new PDSignature();
                signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
                signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
                signature.setName("AssinaLegis");

                JsonNode casa = ConfigService.getInstance().getCasaLegislativa(JsonNode.class);
                if (casa != null && casa.has("nome")) {
                    signature.setLocation(casa.get("nome").asText());
                } else {
                    signature.setLocation("Brasil");
                }

                signature.setReason("Assinatura Digital ICP-Brasil");
                signature.setSignDate(Calendar.getInstance());

                // --- INÍCIO DA CRIAÇÃO DA ASSINATURA VISÍVEL ---

                // Determina página e posição (REGRA_A)
                int pageIndex = 0;
                if (item.getSavedRect() != null) {
                    pageIndex = item.getSavedPageIndex();
                }

                // Validação do índice da página
                if (pageIndex < 0) pageIndex = 0;
                if (pageIndex >= docToSign.getNumberOfPages()) pageIndex = docToSign.getNumberOfPages() - 1;

                PDPage page = docToSign.getPage(pageIndex);
                PDRectangle mediaBox = page.getMediaBox();

                float width = (float) (5.0 / 2.54 * 72); // 5cm em pontos
                float height = (float) (1.5 / 2.54 * 72); // 1.5cm em pontos
                float x = 20;
                float y;

                if (item.getSavedRect() != null) {
                    // REGRA_A_COM_CONTEUDO
                    Rectangle rect = item.getSavedRect();

                    // Converte coordenadas do JavaFX (origem top-left) para PDF
                    // Precisamos considerar que o PDFBox usa 72 DPI por padrão e o viewer usa 200 DPI
                    double scaleFactor = 72.0 / 200.0;

                    x = (float) (rect.getX() * scaleFactor);
                    width = (float) (rect.getWidth() * scaleFactor);
                    height = (float) (rect.getHeight() * scaleFactor);

                    // Ajuste da coordenada Y:
                    // O comportamento observado (y=20 aparecendo no topo) indica que o PDVisibleSignDesigner
                    // nesta versão/configuração está usando origem Top-Left.
                    y = (float) (rect.getY() * scaleFactor);
                } else {
                    // REGRA_A_SEM_CONTEUDO
                    // Canto inferior esquerdo da página
                    // Se origem é Top-Left: y = mediaBox.getHeight() - 20 - height
                    y = (float) (mediaBox.getHeight() - 20 - height);
                }

                // Cria a imagem da assinatura (REGRA_B)
                BufferedImage image = createSignatureImage(width, height, casa);

                // Configurações da assinatura visível
                SignatureOptions signatureOptions = new SignatureOptions();
                signatureOptions.setPage(pageIndex + 1); // PDFBox usa 1-based index para setPage em SignatureOptions? Não, setPage aceita int page number. Vamos verificar.
                // Na verdade, SignatureOptions não tem setPage direto para int em todas as versões, mas vamos usar o VisibleSignatureProperties.
                // Vamos usar a abordagem de criar o visual manualmente e associar ao widget.

                // Criação do visual da assinatura
                try (InputStream docStream = new ByteArrayInputStream(originalBytes);
                     InputStream imageStream = new ByteArrayInputStream(imageToBytes(image));
                     RandomAccessRead raf = new RandomAccessReadBuffer(docStream)) {

                    PDVisibleSignDesigner visibleSignDesigner = new PDVisibleSignDesigner(raf, imageStream, pageIndex + 1);
                    visibleSignDesigner.xAxis(x)
                                       .yAxis(y)
                                       .width(width)
                                       .height(height)
                                       .signatureFieldName("signature");

                    PDVisibleSigProperties visibleSigProperties = new PDVisibleSigProperties();
                    visibleSigProperties.signerName("Assinador")
                            .signerLocation("Jataí")
                            .signatureReason("Assinatura Digital")
                            .preferredSize(0)
                            .page(pageIndex + 1)
                            .visualSignEnabled(true)
                            .setPdVisibleSignature(visibleSignDesigner)
                            .buildSignature();

                    signatureOptions.setVisualSignature(visibleSigProperties);
                }
                signatureOptions.setPage(pageIndex + 1);

                // --- FIM DA CRIAÇÃO DA ASSINATURA VISÍVEL ---

                // 4. Registra a interface de assinatura que fará o trabalho criptográfico
                docToSign.addSignature(signature, new SignatureInterface() {
                    @Override
                    public byte[] sign(InputStream content) throws IOException {
                        try {
                            // Lê o conteúdo do PDF que precisa ser assinado
                            byte[] contentBytes = content.readAllBytes();

                            // Prepara a cadeia de certificados para o Bouncy Castle
                            List<Certificate> certList = new ArrayList<>();
                            for (Certificate cert : certificateChain) {
                                certList.add(cert);
                            }
                            JcaCertStore certs = new JcaCertStore(certList);

                            // Configura o gerador de assinatura CMS (PKCS#7)
                            CMSSignedDataGenerator gen = new CMSSignedDataGenerator();

                            // Define o algoritmo de assinatura (SHA256 com RSA é padrão ICP-Brasil)
                            ContentSigner sha256Signer = new JcaContentSignerBuilder("SHA256withRSA").build(privateKey);

                            gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                                    new JcaDigestCalculatorProviderBuilder().build())
                                    .build(sha256Signer, (X509Certificate) certificateChain[0]));

                            gen.addCertificates(certs);

                            // Gera a assinatura
                            CMSTypedData msg = new CMSProcessableByteArray(contentBytes);
                            // false = detached signature (o PDF contém o conteúdo, a assinatura fica separada na estrutura)
                            CMSSignedData signedData = gen.generate(msg, false);

                            return signedData.getEncoded();
                        } catch (Exception e) {
                            throw new IOException("Erro ao gerar assinatura criptográfica", e);
                        }
                    }
                }, signatureOptions);

                // 5. Salva o documento assinado (Incremental save é obrigatório para assinaturas)
                docToSign.saveIncremental(baosSigned);

                // 6. Carrega o documento assinado e salva no item
                PDDocument signedDoc = Loader.loadPDF(baosSigned.toByteArray());
                item.setPdDocumentSigned(signedDoc);

                //salve também na pasta pessoal do usuário
                String userHome = System.getProperty("user.home");
                String slug = slugify(item.getHeader());
                String fileName = slug + "_assinado.pdf";
                File outputFile = new File(userHome, fileName);
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(baosSigned.toByteArray());
                }
            }
        }
    }

    private String slugify(String input) {
        if (input == null) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String slug = pattern.matcher(normalized).replaceAll("");
        slug = slug.toLowerCase().replaceAll("[^a-z0-9\\-]", "-").replaceAll("-+", "-");
        return slug.replaceAll("^-|-$", "");
    }

    private BufferedImage createSignatureImage(float widthPoints, float heightPoints, JsonNode casa) throws IOException {
        // Converte pontos para pixels (assumindo 300 DPI para boa qualidade)
        int dpi = 300;
        int width = Math.round(widthPoints / 72f * dpi);
        int height = Math.round(heightPoints / 72f * dpi);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        // Configurações de renderização
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Fundo azul (0, 115, 183)
        g2d.setColor(new Color(0, 115, 183));
        g2d.fillRect(0, 0, width, height);

        // Ícone alinhado à direita
        try (InputStream is = App.class.getResourceAsStream("/icon.png")) {
            if (is != null) {
                BufferedImage icon = ImageIO.read(is);
                // Escala o ícone para caber na altura, mantendo proporção
                double scale = (double) height / icon.getHeight();
                int iconWidth = (int) (icon.getWidth() * scale);
                int iconHeight = height; // Ocupa toda a altura

                g2d.drawImage(icon, width - iconWidth, 0, iconWidth, iconHeight, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Texto do nome da Casa Legislativa (Canto Superior Esquerdo)
        String nomeCasa = "Câmara Municipal";
        if (casa != null && casa.has("nome")) {
            nomeCasa = casa.get("nome").asText();
        }

        g2d.setColor(Color.WHITE);
        // Calcula tamanho da fonte para não ultrapassar 70% da largura
        int maxTextWidth = (int) (width * 0.7);
        int fontSize = height / 3; // Começa com um tamanho razoável
        Font font = new Font("SansSerif", Font.BOLD, fontSize);
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();

        while (fm.stringWidth(nomeCasa) > maxTextWidth && fontSize > 5) {
            fontSize--;
            font = new Font("SansSerif", Font.BOLD, fontSize);
            g2d.setFont(font);
            fm = g2d.getFontMetrics();
        }
        g2d.drawString(nomeCasa, 10, fm.getAscent() + 5);

        // Data e Hora (Canto Inferior Esquerdo)
        String dataHora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        int dateFontSize = height / 4;
        Font dateFont = new Font("SansSerif", Font.PLAIN, dateFontSize);
        g2d.setFont(dateFont);
        FontMetrics dateFm = g2d.getFontMetrics();
        g2d.drawString(dataHora, 10, height - dateFm.getDescent() - 5);

        g2d.dispose();
        return image;
    }

    private byte[] imageToBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }
}
