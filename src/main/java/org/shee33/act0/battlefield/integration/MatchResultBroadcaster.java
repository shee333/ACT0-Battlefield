package org.shee33.act0.battlefield.integration;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** 将大战场赛后结果异步上报给 AstrBot QQ 群播报插件。 */
public final class MatchResultBroadcaster {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private MatchResultBroadcaster() {
    }

    public static void sendBattlefieldResult(String battleId,
                                             String matchType,
                                             int durationSeconds,
                                             String winnerDisplay,
                                             List<String> winners,
                                             String topKiller,
                                             int topKills,
                                             String score) {
        if (!enabled()) {
            return;
        }
        String eventId = "act0_battlefield:" + battleId + ":" + durationSeconds;
        String json = "{"
                + field("event_id", eventId) + ","
                + field("source", "act0_battlefield") + ","
                + field("match_id", battleId) + ","
                + field("match_type", matchType) + ","
                + numberField("duration_seconds", durationSeconds) + ","
                + booleanField("draw", false) + ","
                + field("winner_display", winnerDisplay) + ","
                + arrayField("winners", winners) + ","
                + "\"top_killer\":{" + field("name", topKiller) + "," + numberField("kills", topKills) + "},"
                + field("score", score)
                + "}";
        post(json);
    }

    private static void post(String json) {
        String url = value("act0.qqbroadcast.url", "ACT0_QQ_BROADCAST_URL",
                "http://127.0.0.1:18710/act0/match-result");
        String token = value("act0.qqbroadcast.token", "ACT0_QQ_BROADCAST_TOKEN", "act0-local-token");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (!token.isBlank()) {
                builder.header("X-Act0-Token", token);
            }
            CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
                    .thenAccept(resp -> {
                        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                            LOGGER.warn("[ACT0/Battlefield] QQ group broadcast webhook returned HTTP {}", resp.statusCode());
                        }
                    })
                    .exceptionally(ex -> {
                        LOGGER.debug("[ACT0/Battlefield] QQ group broadcast webhook failed: {}", ex.toString());
                        return null;
                    });
        } catch (Exception e) {
            LOGGER.debug("[ACT0/Battlefield] failed to build QQ group broadcast webhook request: {}", e.toString());
        }
    }

    private static boolean enabled() {
        String raw = value("act0.qqbroadcast.enabled", "ACT0_QQ_BROADCAST_ENABLED", "true");
        return !raw.equalsIgnoreCase("false") && !raw.equals("0") && !raw.equalsIgnoreCase("no");
    }

    private static String value(String property, String env, String fallback) {
        String p = System.getProperty(property);
        if (p != null && !p.isBlank()) {
            return p.trim();
        }
        String e = System.getenv(env);
        if (e != null && !e.isBlank()) {
            return e.trim();
        }
        return fallback;
    }

    private static String field(String key, String value) {
        return quote(key) + ":" + quote(value == null ? "" : value);
    }

    private static String numberField(String key, int value) {
        return quote(key) + ":" + value;
    }

    private static String booleanField(String key, boolean value) {
        return quote(key) + ":" + value;
    }

    private static String arrayField(String key, List<String> values) {
        StringBuilder sb = new StringBuilder(quote(key)).append(":");
        sb.append("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(values.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
