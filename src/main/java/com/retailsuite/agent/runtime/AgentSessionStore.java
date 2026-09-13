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
        String key = key(userId, sessionId);
        AgentSession session = sessions.get(key);
        if (session == null) {
            session = new AgentSession(sessionId == null || sessionId.isBlank() ? newSessionId() : sessionId, userId);
            sessions.put(key, session);
        }
        return session;
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
