module br.leg.go.jatai.assinalegis {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.base;
    requires org.apache.pdfbox;
    requires org.bouncycastle.provider;
    requires org.bouncycastle.pkix;
    requires java.prefs;
    requires java.net.http;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;

    opens br.leg.go.jatai.assinalegis to javafx.fxml;
    exports br.leg.go.jatai.assinalegis;
}
