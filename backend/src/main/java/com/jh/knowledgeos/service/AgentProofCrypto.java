package com.jh.knowledgeos.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Strict bounded JWS/JWK parser shared by enrollment and request verification. */
public final class AgentProofCrypto {
    public static final String V1 = "INSTALLATION_RS256_V1";
    public static final String V2 = "AGENT_KEY_RS256_V2";
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private AgentProofCrypto() { }

    public static Map<String, Object> object(String input) {
        try {
            if (input == null || input.length() > 8192) throw new IllegalArgumentException();
            Map<String, Object> result = JSON.readValue(input, new TypeReference<Map<String, Object>>() { });
            if (result == null) throw invalid();
            return result;
        } catch (Exception ex) { throw invalid(); }
    }
    public static RSAKey publicKey(Map<String, Object> value) {
        try {
            Set<String> fields = new HashSet<String>(Arrays.asList("kty", "n", "e", "kid", "alg", "use"));
            if (!fields.equals(value.keySet()) || !"RSA".equals(value.get("kty")) || !"RS256".equals(value.get("alg")) || !"sig".equals(value.get("use"))) throw invalid();
            if (!text(value, "n", 683).matches("[A-Za-z0-9_-]{512,683}") || !"AQAB".equals(value.get("e"))
                || !text(value, "kid", 43).matches("[A-Za-z0-9_-]{43}")) throw invalid();
            RSAKey key = RSAKey.parse(value);
            RSAPublicKey publicKey = key.toRSAPublicKey();
            int bits = publicKey.getModulus().bitLength();
            if (key.isPrivate() || bits < 3072 || bits > 4096 || !BigInteger.valueOf(65537).equals(publicKey.getPublicExponent())
                || !"AQAB".equals(value.get("e")) || !com.nimbusds.jose.util.Base64URL.encode(publicKey.getModulus()).toString().equals(value.get("n"))
                || !key.computeThumbprint().toString().equals(value.get("kid"))) throw invalid();
            return key;
        } catch (Exception ex) { throw invalid(); }
    }
    public static Proof parse(String token, String type) {
        try {
            if (token == null || token.length() > 8192) throw invalid();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw invalid();
            for (String part : parts) if (!part.matches("[A-Za-z0-9_-]+")) throw invalid();
            Map<String, Object> header = object(utf8(Base64.getUrlDecoder().decode(parts[0])));
            if (!header.keySet().equals(new HashSet<String>(Arrays.asList("alg", "typ", "kid"))) || !"RS256".equals(header.get("alg"))
                || !type.equals(header.get("typ")) || !text(header, "kid", 43).matches("[A-Za-z0-9_-]{43}")) throw invalid();
            return new Proof(header, object(utf8(Base64.getUrlDecoder().decode(parts[1]))),
                (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception ex) { throw invalid(); }
    }
    private static String utf8(byte[] value) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
    }
    public static final class Proof {
        public final Map<String, Object> header;
        public final Map<String, Object> claims;
        private final byte[] input, signature;
        private Proof(Map<String, Object> header, Map<String, Object> claims, byte[] input, byte[] signature) {
            this.header = header; this.claims = claims; this.input = input; this.signature = signature;
        }
        public void verify(RSAKey key) {
            try {
                if (!key.getKeyID().equals(header.get("kid"))) throw invalid();
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(key.toRSAPublicKey()); verifier.update(input);
                if (!verifier.verify(signature)) throw invalid();
            } catch (Exception ex) { throw invalid(); }
        }
    }
    public static String text(Map<String, Object> map, String name, int max) {
        Object value = map.get(name);
        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > max
            || ((String) value).matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw invalid();
        return (String) value;
    }
    public static long integer(Map<String, Object> map, String name) {
        Object value = map.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        return ((Number) value).longValue();
    }
    public static Instant expiry(Map<String, Object> claims, long maxTtl) {
        long issued = integer(claims, "iat"), expiry = integer(claims, "exp"), now = Instant.now().getEpochSecond();
        if (issued < 0 || expiry <= now || issued > now + 30 || expiry <= issued || expiry - issued > maxTtl) throw invalid();
        return Instant.ofEpochSecond(expiry);
    }
    public static String uuid(String value) {
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
        return value;
    }
    public static Instant instant(Object value) {
        if (value instanceof Timestamp) return ((Timestamp) value).toInstant();
        throw invalid();
    }
    public static boolean equal(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    public static IllegalArgumentException invalid() { return new IllegalArgumentException("无效的 Agent 身份证明或密钥登记"); }
}
