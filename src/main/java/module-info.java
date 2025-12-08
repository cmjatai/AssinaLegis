module br.leg.go.jatai.assinalegis {
    requires javafx.controls;
    requires javafx.fxml;
    requires transitive javafx.graphics;
    requires javafx.base;
    requires javafx.swing;
    requires java.desktop;
    requires transitive org.apache.pdfbox;
    requires org.apache.pdfbox.io;
    requires org.bouncycastle.provider;
    requires org.bouncycastle.pkix;
    requires java.prefs;
    requires java.naming;
    requires java.net.http;
    requires transitive com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires okhttp3;
    requires okio;

    opens br.leg.go.jatai.assinalegis to javafx.fxml;
    exports br.leg.go.jatai.assinalegis;
}
