package com.retailsuite.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * JWT 签发与校验（HS256）。
 *
 * 为什么手写而不是引 jjwt：一是少一个依赖少一类版本冲突，二是这段代码本身就是面试考点
 * （base64url 编码、HMAC 签名、签名定长时间比较、过期与签发者校验）。
 *
 * 设计取舍（也要能讲）：
 * - 无状态：权限码写在令牌里，省掉每次请求查库；代价是**改权限后需要重新登录**才生效。
 *   真到权限需要即时失效的阶段，做法是把令牌版本号写进用户表/Redis，校验时比对版本（或维护黑名单）。
 * - 密钥来自配置（生产必须用环境变量覆盖），签名比较用 MessageDigest.isEqual 避免时序侧信道。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtService {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public String issue(AuthUser user) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        long expireSeconds = nowSeconds + properties.getJwt().getAccessTokenMinutes() * 60L;

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getJwt().getIssuer());
        claims.put("sub", user.username());
        claims.put("uid", user.userId());
        claims.put("sid", user.storeId());
        claims.put("name", user.realName());
        claims.put("roles", user.roleCodes());
        claims.put("perms", user.permissions());
        claims.put("iat", nowSeconds);
        claims.put("exp", expireSeconds);

        String signingInput = encodeJson(header) + "." + encodeJson(claims);
        return signingInput + "." + ENCODER.encodeToString(sign(signingInput));
    }

    /** 校验签名、签发者与过期时间，返回认证上下文；任何不合法都抛 401。 */
    public AuthUser verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少令牌");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌格式不合法");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌签名段不是合法 base64url");
        }
        // 定长时间比较：避免通过响应时间差逐字节猜测签名
        if (!MessageDigest.isEqual(sign(signingInput), actual)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌签名不合法");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(DECODER.decode(parts[1]));
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌载荷解析失败");
        }
        if (!properties.getJwt().getIssuer().equals(payload.path("iss").asText())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌签发者不匹配");
        }
        long expireSeconds = payload.path("exp").asLong();
        if (expireSeconds * 1000 < System.currentTimeMillis()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        return new AuthUser(
                payload.path("uid").asLong(),
                payload.path("sid").asLong(),
                payload.path("sub").asText(),
                payload.path("name").asText(null),
                toStringSet(payload.path("roles")),
                toStringSet(payload.path("perms")));
    }

    /** 令牌剩余秒数（前端用来提示"快要过期了"）。 */
    public long remainingSeconds(String token) {
        try {
            String[] parts = token.split("\\.");
            JsonNode payload = objectMapper.readTree(DECODER.decode(parts[1]));
            return Math.max(0, payload.path("exp").asLong() - System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            return 0;
        }
    }

    private Set<String> toStringSet(JsonNode arrayNode) {
        Set<String> values = new LinkedHashSet<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.forEach(node -> values.add(node.asText()));
        }
        return Set.copyOf(values);
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 载荷序列化失败", e);
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }
}
