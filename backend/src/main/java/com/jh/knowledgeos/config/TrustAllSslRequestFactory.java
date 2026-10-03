package com.jh.knowledgeos.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Request factory that accepts any server certificate.
 *
 * <p>Internal Jira / Confluence / self-built REST endpoints are very often served over HTTPS with a
 * self-signed certificate, or reached by IP so that no hostname matches. The JVM trust store knows
 * neither, and the handshake dies with {@code PKIX path building failed} before a single byte of
 * knowledge is read. Operators need a way to say "this source is mine, trust it".</p>
 *
 * <p>Deliberately scoped: this factory is only wired into the connector reader and only used when a
 * connector (or the global switch) opts in, so every other outbound call keeps full certificate
 * validation.</p>
 */
public final class TrustAllSslRequestFactory extends SimpleClientHttpRequestFactory {

    private final SSLSocketFactory socketFactory;

    public TrustAllSslRequestFactory() {
        this.socketFactory = trustAllSocketFactory();
    }

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection secure = (HttpsURLConnection) connection;
            secure.setSSLSocketFactory(socketFactory);
            // 用 IP 访问时证书里的主机名对不上，主机名校验同样要放开。
            secure.setHostnameVerifier((hostname, session) -> true);
        }
    }

    private static SSLSocketFactory trustAllSocketFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new PermissiveTrustManager()}, new SecureRandom());
            return context.getSocketFactory();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("初始化「跳过证书校验」的 SSL 上下文失败", ex);
        }
    }

    /** Accepts every chain without inspection; the caller already decided the source is trusted. */
    private static final class PermissiveTrustManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // 连接器只做单向校验，客户端证书不参与判定。
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // 有意留空：信任决策已由配置显式表达，并会写入审计。
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
