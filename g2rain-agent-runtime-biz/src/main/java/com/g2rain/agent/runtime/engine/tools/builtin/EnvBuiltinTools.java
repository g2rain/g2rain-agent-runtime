package com.g2rain.agent.runtime.engine.tools.builtin;


import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.agent.runtime.support.tool.BuiltinToolKind;
import com.g2rain.agent.runtime.support.tool.BuiltinTools;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * EnvBuiltinTools（环境辅助内置工具）：时间、UUID、随机数、哈希等无状态查询。
 * <p>
 * 不碰任务记忆与节点控流，只给模型算值用。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
// @Component
public class EnvBuiltinTools implements BuiltinTools {

    /**
     * 取当前系统时间，格式 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * <p>例：{@code currentDateTime()} → {@code 2026-08-21 10:00:00}</p>
     *
     * @return 当前时间字符串，例如 {@code 2026-08-21 10:00:00}
     */
    @Tool(
        name = "builtin_env_current_datetime",
        description = """
            返回当前时间 (yyyy-MM-dd HH:mm:ss)
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.ENV, action = BuiltinToolAction.QUERY)
    public ScalarResult<String> currentDateTime() {
        return new ScalarResult<>(Moments.nowString());
    }

    /**
     * 生成去横线 UUID 十六进制串，再按 {@code length} 截前缀。
     *
     * <p>未传 {@code length} 时按 32；实际取值夹到 1..32。例：{@code generateUuid(8)}</p>
     *
     * @param length 截断长度；可空，默认 32，合法区间 1..32
     * @return 截断后的十六进制串，例如 {@code length=8} 得到 8 位
     */
    @Tool(
        name = "builtin_env_generate_uuid",
        description = """
            生成 UUID (去横线), 可选 length 截断, 默认值: 32
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.ENV, action = BuiltinToolAction.QUERY)
    public ScalarResult<String> generateUuid(@ToolParam(description = "截断长度 1..32，默认 32", required = false) @Nullable Integer length) {
        // 夹到合法区间后再截前缀
        int len = Math.clamp(Objects.requireNonNullElse(length, 32), 1, 32);
        String value = UUID.randomUUID().toString().replace("-", "");
        return new ScalarResult<>(value.substring(0, len));
    }

    /**
     * 按指定 pattern 解析再格式化日期时间。
     *
     * <p>{@code input} 空白则用当前时间。输入/输出 pattern 空白则用 Moments 默认 {@code yyyy-MM-dd HH:mm:ss}。
     * 例：{@code formatDateTime("2026-08-21 10:00:00", null, "yyyy-MM-dd")}</p>
     *
     * @param input         输入时间；可空，空白则用现在。例 {@code 2026-08-21 10:00:00}
     * @param inputPattern  输入 pattern；可空，默认 {@code yyyy-MM-dd HH:mm:ss}
     * @param outputPattern 输出 pattern；可空，默认 {@code yyyy-MM-dd HH:mm:ss}
     * @return 按输出 pattern 格式化后的时间串
     */
    @Tool(
        name = "builtin_env_format_datetime",
        description = """
            按指定 pattern 格式化日期
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.ENV, action = BuiltinToolAction.QUERY)
    public ScalarResult<String> formatDateTime(@ToolParam(description = "输入时间, 例如 2026-08-21 10:00:00") @Nullable String input,
                                               @ToolParam(description = "输入 pattern, 默认 yyyy-MM-dd HH:mm:ss", required = false) @Nullable String inputPattern,
                                               @ToolParam(description = "输出 pattern, 默认 yyyy-MM-dd HH:mm:ss", required = false) @Nullable String outputPattern) {

        // 未给 input 时直接用现在
        LocalDateTime dateTime = Strings.isBlank(input) ? Moments.now() : Moments.parse(input, Strings.defaultIfBlank(inputPattern, Moments.DEFAULT_PATTERN));
        return new ScalarResult<>(Moments.format(dateTime, Strings.defaultIfBlank(outputPattern, Moments.DEFAULT_PATTERN)));
    }

    /**
     * 解析日期并返回星期几。
     *
     * <p>{@code date} 按 {@code LocalDate} ISO 日期解析（如 {@code 2026-08-21}）。
     * 回包含枚举名 {@code MONDAY}..{@code SUNDAY} 与 ISO-8601 数值 1..7（周一=1）。
     * 例：{@code getWeekday("2026-08-21")}</p>
     *
     * @param date 日期字符串，ISO {@code LocalDate} 可解析，如 {@code 2026-08-21}
     * @return 星期枚举名与 ISO 数值
     */
    @Tool(
        name = "builtin_env_get_weekday",
        description = """
            返回星期几 (MONDAY..SUNDAY) 与 ISO 值 1..7
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.ENV, action = BuiltinToolAction.QUERY)
    public WeekdayResult getWeekday(@ToolParam(description = "日期时间字符串") String date) {
        DayOfWeek dow = LocalDate.parse(date).getDayOfWeek();
        return new WeekdayResult(dow.name(), dow.getValue());
    }

    /**
     * 在闭区间 {@code [min, max]} 内取随机整数。
     *
     * <p>{@code max < min} 时抛错。例：{@code randomInt(1, 6)}</p>
     *
     * @param min 下界（含）
     * @param max 上界（含）
     * @return 随机整数
     */
    @Tool(
        name = "builtin_env_random_int",
        description = """
            闭区间 [min,max] 随机整数
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.ENV, action = BuiltinToolAction.QUERY)
    public ScalarResult<Integer> randomInt(@ToolParam(description = "下界(含)") int min, @ToolParam(description = "上界(含)") int max) {
        if (max < min) {
            throw new IllegalArgumentException("max < min");
        }

        return new ScalarResult<>(min + ThreadLocalRandom.current().nextInt(max - min + 1));
    }

    /**
     * 对原文做哈希，输出小写十六进制摘要。
     *
     * <p>{@code algorithm} 空白默认 {@code SHA-256}。{@code length} 空则全长，否则截前缀并夹到 1..摘要长度。
     * {@code input} 空白或算法名不受支持时抛错。
     * 例：{@code hash("hello", "SHA-256", 16)}</p>
     *
     * @param input     原文，必填
     * @param algorithm 哈希算法名；可空，默认 SHA-256
     * @param length    截断长度；可空则全长
     * @return 十六进制摘要（可截断）
     */
    @Tool(
        name = "builtin_env_hash",
        description = """
            对 input 做哈希, algorithm 默认 SHA-256, 可选 length 截断
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.ENV, action = BuiltinToolAction.QUERY)
    public ScalarResult<String> hash(@ToolParam(description = "原文") String input,
                                     @ToolParam(description = "算法，默认 SHA-256", required = false) @Nullable String algorithm,
                                     @ToolParam(description = "截断长度；空则全长", required = false) @Nullable Integer length) {
        if (Strings.isBlank(input)) {
            throw new IllegalArgumentException("input 必填");
        }

        String alg = Strings.isBlank(algorithm) ? "SHA-256" : algorithm.trim();
        try {
            byte[] digest = MessageDigest.getInstance(alg).digest(input.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            // length 空则取全长，再夹到 [1, hex.length]
            int len = Math.clamp(Objects.requireNonNullElse(length, hex.length()), 1, hex.length());
            return new ScalarResult<>(hex.substring(0, len));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("不支持的哈希算法: " + alg, e);
        }
    }

    /**
     * 环境工具标量回包。
     *
     * @param value 计算结果；可空
     * @param <T>   值类型
     */
    public record ScalarResult<T>(@Nullable T value) {

    }

    /**
     * 星期查询回包。
     *
     * @param value 星期枚举名（如 MONDAY）
     * @param iso   ISO-8601 星期值 1..7
     */
    public record WeekdayResult(String value, int iso) {

    }
}
