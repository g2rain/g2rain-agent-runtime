package com.g2rain.agent.runtime.domain.hydrate;

import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Runtime 当前能理解的契约格式版本。
 *
 * <p>版本串来自契约 JSON 的根属性 {@code version}，格式为 {@code 主版本.次版本}，两段均为非负整数，
 * 第一版为 {@code 1.0}。本常量表示当前 Runtime 发布所具备的契约理解能力：能解析、校验并执行的契约格式上界。</p>
 *
 * <p><b>兼容判断</b>：接受一个契约，当且仅当契约主版本等于 Runtime 主版本，
 * 且契约次版本小于或等于 Runtime 次版本。缺少 {@code version} 或格式非法（空、非两段整数）不做猜测，
 * 按 {@link Verdict#INVALID} 处理。</p>
 *
 * <p><b>何时升版本</b>：字段改名、删除或含义改变等不兼容修改升主版本（如 {@code 1.x} → {@code 2.0}），
 * 并由脚本整体迁移契约数据，Runtime 不保留旧主版本处理代码；兼容的新增（如新增可选字段）升次版本
 * （如 {@code 1.0} → {@code 1.1}）。</p>
 *
 * <p><b>为何写死在代码里</b>：它跟随 Runtime 的发布版本，是代码能力本身的属性，不是部署参数，
 * 因此不放配置。</p>
 *
 * @author alpha
 * @since 2026/11/24
 */
public final class ContractVersion {

    /**
     * 当前 Runtime 支持的契约格式版本。
     */
    public static final String CURRENT = "1.0";

    /**
     * {@link #CURRENT} 的解析结果；启动时解析一次。
     */
    private static final Version CURRENT_PARSED = Objects.requireNonNull(parse(CURRENT));

    private ContractVersion() {
    }

    /**
     * 对照 {@link #CURRENT} 判断契约根属性 {@code version}。
     *
     * @param version 契约上的版本串；可空
     * @return 判断结果
     */
    public static Verdict check(@Nullable String version) {
        Version candidate = parse(version);
        if (Objects.isNull(candidate)) {
            return Verdict.INVALID;
        }

        if (candidate.major() < CURRENT_PARSED.major()) {
            return Verdict.OUTDATED;
        }

        if (candidate.major() > CURRENT_PARSED.major()
            || candidate.minor() > CURRENT_PARSED.minor()) {
            return Verdict.AHEAD;
        }

        return Verdict.ACCEPTED;
    }

    /**
     * 解析 {@code 主版本.次版本}；非法则返回 {@code null}。
     *
     * @param version 版本串；可空
     * @return 解析结果；非法为 {@code null}
     */
    private static @Nullable Version parse(@Nullable String version) {
        if (Strings.isBlank(version)) {
            return null;
        }

        int firstDot = version.indexOf('.');
        if (firstDot <= 0 || firstDot != version.lastIndexOf('.') || firstDot == version.length() - 1) {
            return null;
        }

        Integer major = parseNonNegativeInt(version, 0, firstDot);
        Integer minor = parseNonNegativeInt(version, firstDot + 1, version.length());
        if (Objects.isNull(major) || Objects.isNull(minor)) {
            return null;
        }

        return new Version(major, minor);
    }

    /**
     * 把闭开区间内的数字字符读成非负整数：先确认全是数字，再用 {@link Integer#parseInt}。
     *
     * @param text 原文
     * @param from 起始下标（含）
     * @param to   结束下标（不含）
     * @return 整数值；区间为空、含非数字或超出 int 范围时为 {@code null}
     */
    private static @Nullable Integer parseNonNegativeInt(String text, int from, int to) {
        if (from >= to) {
            return null;
        }

        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }

        try {
            return Integer.parseInt(text, from, to, 10);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 契约格式版本对照 {@link #CURRENT} 的判断结果。
     */
    public enum Verdict {

        /**
         * 主版本相同且次版本不高于当前 Runtime。
         */
        ACCEPTED,

        /**
         * 缺少或格式非法。
         */
        INVALID,

        /**
         * 契约主版本低于 Runtime 主版本，须升级契约。
         */
        OUTDATED,

        /**
         * 契约主版本更高，或主版本相同而次版本更高，须升级 Runtime。
         */
        AHEAD
    }

    /**
     * 已解析的主、次版本。
     *
     * @param major 主版本
     * @param minor 次版本
     */
    private record Version(int major, int minor) {

    }
}
