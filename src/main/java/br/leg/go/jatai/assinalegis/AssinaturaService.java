package br.leg.go.jatai.assinalegis;

import br.leg.go.jatai.assinalegis.DocumentViewerController.DocumentItem;
import com.fasterxml.jackson.databind.JsonNode;

import javafx.scene.shape.Rectangle;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSigProperties;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSignDesigner;
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

        // Extrai o nome do assinante (CN) do certificado
        String nomeAssinante = "";
        try {
            X509Certificate x509Cert = (X509Certificate) certificateChain[0];
            String subjectDN = x509Cert.getSubjectX500Principal().getName();
            javax.naming.ldap.LdapName ln = new javax.naming.ldap.LdapName(subjectDN);
            for (javax.naming.ldap.Rdn rdn : ln.getRdns()) {
                if (rdn.getType().equalsIgnoreCase("CN")) {
                    nomeAssinante = rdn.getValue().toString();
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Inverte a lista para assinar na ordem correta (se necessário)
        List<DocumentItem> itensInvertidos = new ArrayList<>(itens);
        java.util.Collections.reverse(itensInvertidos);

        for (DocumentItem item : itensInvertidos) {
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
                float x = 7;
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
                    // Se origem é Top-Left: y = mediaBox.getHeight() - 7 - height
                    y = (float) (mediaBox.getHeight() - 7 - height);
                }

                // Cria a imagem da assinatura (REGRA_B)
                BufferedImage image = createSignatureImage(width, height, casa, nomeAssinante);

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
                signatureOptions.setPage(pageIndex);

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

                byte[] signedBytes = baosSigned.toByteArray();

                // 6. Carrega o documento assinado e salva no item
                PDDocument signedDoc = Loader.loadPDF(signedBytes);
                item.setPdDocumentSigned(signedDoc);
                item.setSignedBytes(signedBytes);

                //salve também na pasta pessoal do usuário
                if (ConfigService.getInstance().isDebug()) {
                    String userHome = System.getProperty("user.home");
                    String slug = slugify(item.getHeader());
                    String fileName = slug + "_assinado.pdf";
                    File outputFile = new File(userHome, fileName);
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        fos.write(signedBytes);
                    }
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


    private BufferedImage createSignatureImage(float widthPoints, float heightPoints, JsonNode casa, String nomeAssinante) throws IOException {
        // Converte pontos para pixels (assumindo 300 DPI para boa qualidade)
        int dpi = 300;
        int width = Math.round(widthPoints / 72f * dpi);
        int height = Math.round(heightPoints / 72f * dpi);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        String fontName = "Liberation Sans Narrow";
        // Configurações de renderização
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        ConfigService configService = ConfigService.getInstance();
        String bgColorHex = configService.getSignatureBgColor();
        Color bgColor = Color.decode(bgColorHex);

        String nameColorHex = configService.getSignatureNameColor();
        Color nameColor = Color.decode(nameColorHex);
        String dateColorHex = configService.getSignatureDateColor();
        Color dateColor = Color.decode(dateColorHex);


        // Fundo azul (0, 100, 170) com bordas arredondadas
        g2d.setColor(bgColor);
        int arc = 40;
        java.awt.geom.RoundRectangle2D roundedRect = new java.awt.geom.RoundRectangle2D.Float(0, 0, width, height, arc, arc);
        g2d.fill(roundedRect);

        // Aplica o clip para que o gradiente respeite as bordas arredondadas
        Shape oldClip = g2d.getClip();
        g2d.setClip(roundedRect);

        drawRotatedGradient(g2d, new java.awt.Rectangle(0, 0, width, height), -210, 80);
        drawRotatedGradient(g2d, new java.awt.Rectangle(0, (int)(height*0.7), width, (int)(height*0.7)), 30, 80);

        // Restaura o clip original
        g2d.setClip(oldClip);

        // Ícone alinhado à direita
        BufferedImage icon = null;

        // Tenta carregar do logotipo da casa (URL)
        if (casa != null && casa.has("logotipo") && !casa.get("logotipo").isNull() && !casa.get("logotipo").asText().equals("null")) {
            String logoUrl = casa.get("logotipo").asText();
            if (logoUrl != null && !logoUrl.isEmpty()) {
                try {
                    java.net.URL url = java.net.URI.create(logoUrl).toURL();
                    icon = ImageIO.read(url);
                } catch (Exception e) {
                    System.err.println("Erro ao carregar logotipo da URL: " + logoUrl);
                    e.printStackTrace();
                }
            }
        }

        g2d.setColor(Color.WHITE);

        String nomeCasa = "Câmara Municipal";
        if (casa != null && casa.has("nome")) {
            nomeCasa = casa.get("nome").asText();
        }
        // Calcula tamanho da fonte para não ultrapassar 65% da largura
        int maxTextWidth = (int) (width * 0.65);
        int fontSize = height / 3; // Começa com um tamanho razoável
        Font font = new Font(fontName, Font.BOLD, fontSize);
        g2d.setColor(nameColor);
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();

        while (fm.stringWidth(nomeCasa) > maxTextWidth && fontSize > 5) {
            fontSize--;
            font = new Font(fontName, Font.BOLD, fontSize);
            g2d.setFont(font);
            fm = g2d.getFontMetrics();
        }
        // Desenha o nome da casa (Canto Superior Direito da área de texto)
        int xNomeCasa = (int) (width) - fm.stringWidth(nomeCasa) - 10;
        g2d.drawString(nomeCasa, xNomeCasa, fm.getAscent());


        g2d.setColor(nameColor);
        // Nome do Assinante (Centralizado na Vertical, Alinhado à Esquerda)
        if (nomeAssinante == null) nomeAssinante = "";
        if (nomeAssinante.contains(":")) {
            nomeAssinante = nomeAssinante.split(":")[0];
        }
        nomeAssinante = nomeAssinante.toUpperCase();
        // Calcula tamanho da fonte para não ultrapassar 70% da largura
        maxTextWidth = (int) (width * 0.7);
        fontSize = height / 3; // Começa com um tamanho razoável
        font = new Font(fontName, Font.BOLD, fontSize);
        g2d.setFont(font);
        fm = g2d.getFontMetrics();
        while (fm.stringWidth(nomeAssinante) > maxTextWidth && fontSize > 5) {
            fontSize--;
            font = new Font(fontName, Font.BOLD, fontSize);
            g2d.setFont(font);
            fm = g2d.getFontMetrics();
        }
        // Centralizado na vertical
        int yText = (int)(height / 2 - fm.getHeight()*1.3) + fm.getAscent();
        g2d.drawString(nomeAssinante, 10, yText);


        String dataHora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm"));
        int dateFontSize = (int) (fontSize * 0.9);
        Font dateFont = new Font(fontName, Font.BOLD, dateFontSize);
        g2d.setColor(dateColor);
        g2d.setFont(dateFont);
        FontMetrics dateFm = g2d.getFontMetrics();
        yText = (int)(height + dateFm.getHeight()) / 2;
        g2d.drawString(dataHora, 40, yText);


        g2d.setColor(dateColor);
        font = new Font(fontName, Font.BOLD, 20);
        g2d.setFont(font);
        fm = g2d.getFontMetrics();
        String data = "ASSINATURA QUALIFICADA ICP-BRASIL";
        int hfm = fm.getHeight();
        g2d.drawString(data, 20, (int)(height*0.68)+hfm);


        g2d.setColor(nameColor);
        font = new Font(fontName, Font.BOLD, 20);
        g2d.setFont(font);
        fm = g2d.getFontMetrics();
        data = "Validação disponível em: https://validar.iti.gov.br";
        hfm = fm.getHeight();
        g2d.drawString(data, 20, (int)(height-hfm/2.4));


        // Fallback para o ícone padrão se não conseguiu carregar o logotipo
        if (icon == null) {
            try (InputStream is = App.class.getResourceAsStream("/icon.png")) {
                if (is != null) {
                    icon = ImageIO.read(is);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (icon != null) {
            // Define a área máxima para o ícone (30% da largura e 85% da altura)
            double maxIconWidth = width * 0.27; // 27% para deixar uma pequena margem
            double maxIconHeight = height * 0.85;

            double scaleWidth = maxIconWidth / icon.getWidth();
            double scaleHeight = maxIconHeight / icon.getHeight();

            // Usa a menor escala para garantir que cabe em ambas as dimensões sem distorcer
            double scale = Math.min(scaleWidth, scaleHeight);

            int iconWidth = (int) (icon.getWidth() * scale);
            int iconHeight = (int) (icon.getHeight() * scale);

            // Centralizado na vertical
            int yPos = (height - iconHeight) - 10;

            // Centralizado horizontalmente na área dos 30% à direita
            int areaWidth = (int) (width * 0.3);
            int areaStart = (int) (width - areaWidth - 10); // margem direita de 10 pixels
            int xPos = areaStart + (areaWidth - iconWidth);

            g2d.drawImage(icon, xPos, yPos, iconWidth, iconHeight, null);
        }

        g2d.dispose();
        return image;
    }

    private void drawRotatedGradient(Graphics2D g2d, java.awt.Rectangle rect, double rotationDegrees, int maxAlpha) {
        if (rect == null) {
            return;
        }

        // Calcula o centro do retângulo
        double cx = rect.getCenterX();
        double cy = rect.getCenterY();

        // Converte o ângulo para radianos e calcula seno/cosseno
        double rad = Math.toRadians(rotationDegrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        // Encontra os pontos extremos projetados no vetor do gradiente
        double minProj = Double.MAX_VALUE;
        double maxProj = -Double.MAX_VALUE;

        // Coordenadas dos 4 cantos
        double[][] corners = {
            {rect.getMinX(), rect.getMinY()},
            {rect.getMaxX(), rect.getMinY()},
            {rect.getMaxX(), rect.getMaxY()},
            {rect.getMinX(), rect.getMaxY()}
        };

        for (double[] corner : corners) {
            // Vetor do centro até o canto
            double dx = corner[0] - cx;
            double dy = corner[1] - cy;

            // Projeção escalar no vetor de direção do gradiente
            double proj = dx * cos + dy * sin;

            if (proj < minProj) minProj = proj;
            if (proj > maxProj) maxProj = proj;
        }

        // Calcula os pontos inicial e final do gradiente baseados nas projeções extremas
        float x1 = (float) (cx + minProj * cos);
        float y1 = (float) (cy + minProj * sin);
        float x2 = (float) (cx + maxProj * cos);
        float y2 = (float) (cy + maxProj * sin);

        // Cria o gradiente: Branco transparente (0) -> Branco com transparência definida (maxAlpha)
        Color startColor = new Color(255, 255, 255, 0);
        Color endColor = new Color(255, 255, 255, maxAlpha);

        GradientPaint gradient = new GradientPaint(x1, y1, startColor, x2, y2, endColor);

        g2d.setPaint(gradient);
        g2d.fill(rect);
    }

    private byte[] imageToBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }
}
