package br.leg.go.jatai.assinalegis;

import java.util.prefs.Preferences;

public class ConfigService {

    private static final String KEY_URL = "url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_CERT_PATH = "cert_path";

    private final Preferences prefs;

    public ConfigService() {
        this.prefs = Preferences.userNodeForPackage(App.class);
    }

    public String getUrl() {
        return prefs.get(KEY_URL, "");
    }

    public void setUrl(String url) {
        prefs.put(KEY_URL, url);
    }

    public String getToken() {
        return prefs.get(KEY_TOKEN, "");
    }

    public void setToken(String token) {
        prefs.put(KEY_TOKEN, token);
    }

    public String getCertPath() {
        return prefs.get(KEY_CERT_PATH, "");
    }

    public void setCertPath(String certPath) {
        prefs.put(KEY_CERT_PATH, certPath);
    }
}
