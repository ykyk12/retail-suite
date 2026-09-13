package com.retailsuite.agent.runtime;

import com.retailsuite.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话记忆：同一用户的多轮对话上下文。
 *
 * 企业级的取舍（都要说清楚）：
 * - 现在放内存 + TTL 清理：零依赖、够单店用；多实例部署需要换 Redis（接口已隔离，替换 SessionStore 即可）；
 * - 只保留最近 N 轮：长会话不裁剪会把上下文与成本一起拖爆；
 * - 记忆是"每个用户一份"的：不同店员的对话不串（user 维度隔离），并且**不跨账号共享**。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSessionStore {

    private final AppProperties properties;
    private final Map<String, AgentSession> sessions = new LinkedHashMap<>();

    /** 一轮对话（用户问了什么、管家答了什么、用了哪些工具）。 */
    public record Turn(String question, String answer, List<String> toolsUsed, LocalDateTime at) {
    }

    /** 单个会话：用户 + 轮次列表。 */
    public static final class AgentSession {

        private final String sessionId;
        private final Long userId;
        private final List<Turn> turns = new ArrayList<>();
        private LocalDateTime lastActiveAt = LocalDateTime.now();

        AgentSession(String sessionId, Long userId) {
            this.sessionId = sessionId;
            this.userId = userId;
        }

        public String sessionId() {
            return sessionId;
        }

        public Long userId() {
            return userId;
        }

        public synchronized void append(Turn turn) {
            turns.add(turn);
            lastActiveAt = LocalDateTime.now();
        }

        public synchronized List<Turn> turns() {
            return List.copyOf(turns);
        }

        synchronized void trim(int maxTurns) {
            while (turns.size() > maxTurns) {
                turns.remove(0);
            }
        }

        synchronized LocalDateTime lastActiveAt() {
            return lastActiveAt;
        }

        /** 给模型看的对话历史（最近几轮，压缩成一段文本，避免消息数组无限增长）。 */
        public synchronized String historyDigest(int maxTurns) {
            List<Turn> recent = turns.size() <= maxTurns ? turns() : turns().subList(turns.size() - maxTurns, turns.size());
            if (recent.isEmpty()) {
                return "(这是本轮对话的第一句)";
            }
            StringBuilder sb = new StringBuilder();
            for (Turn turn : recent) {
                sb.append("用户：").append(turn.question()).append('\n')
                        .append("管家：").append(abbreviate(turn.answer())).append('\n');
            }
            return sb.toString();
        }

        private String abbreviate(String text) {
            if (text == null) {
                return "";
            }
            return text.length() <= 200 ? text : text.substring(0, 200) + "...";
        }
    }

    public synchronized AgentSession getOrCreate(Long userId, String sessionId) {
        purgeExpired();
        if (sessionId == null || sessionId.isBlank()) {
            // 不带 sessionId = "接着这个用户当前的对话"（前端刷新页面后再问一句，上下文不该丢）
            return sessions.computeIfAbsent(key(userId, null), k -> new AgentSession(newSessionId(), userId));
        }
        // 带上 sessionId 时先按 id 在已有会话里找：
        // 否则"不传 id 拿到 S1"再"带着 S1 追问"会被拆成两个记忆桶，同一段对话前后半段互相看不见
        for (AgentSession session : sessions.values()) {
            if (userId.equals(session.userId()) && sessionId.equals(session.sessionId())) {
                return session;
            }
        }
        return sessions.computeIfAbsent(key(userId, sessionId), k -> new AgentSession(sessionId, userId));
    }

    /**
     * 开一个全新会话（界面上「新会话」按钮）。
     *
     * 必须显式做这件事：不带 sessionId 的请求是"接着上次聊"，光在前端丢掉 sessionId 是清不掉上下文的。
     *
     * @return 新会话 id
     */
    public synchronized String reset(Long userId) {
        purgeExpired();
        sessions.keySet().removeIf(key -> key.startsWith(userId + "::"));
        AgentSession session = new AgentSession(newSessionId(), userId);
        // 同时挂到"默认桶"上：不带 sessionId 的请求是"接着该用户当前对话"，
        // 只放进 id 桶的话，reset 之后用户随手再问一句又会掉回旧上下文（这一点被评测集抓到过）
        sessions.put(key(userId, null), session);
        sessions.put(key(userId, session.sessionId()), session);
        return session.sessionId();
    }

    public synchronized Optional<AgentSession> find(Long userId, String sessionId) {
        return Optional.ofNullable(sessions.get(key(userId, sessionId)));
    }

    public synchronized int size() {
        return sessions.size();
    }

    public synchronized void clear() {
        sessions.clear();
    }

    private void purgeExpired() {
        int ttlMinutes = properties.getAi().getSessionTtlMinutes();
        if (ttlMinutes <= 0) {
            return;
        }
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(ttlMinutes);
        sessions.entrySet().removeIf(entry -> entry.getValue().lastActiveAt().isBefore(deadline));
    }

    private String key(Long userId, String sessionId) {
        return userId + "::" + (sessionId == null ? "" : sessionId);
    }

    private String newSessionId() {
        return "S" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
