package com.g2rain.agent.runtime.domain.hydrate;


import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

/**
 * 参数 {@code from} 解析结果：数据袋 + 袋内相对路径。
 *
 * <p>装载期由 {@link ArgSpecHydrate} 调用 {@link #parse(String)}；非法前缀抛
 * {@code ILLEGAL_SOURCE}。{@link #isKnown} 只做形态判断，不抛异常。</p>
 *
 * @param bag  数据袋
 * @param path 袋内路径；EVIDENCE 时为 {@code extractKey} 或 {@code extractKey.subpath}
 * @author alpha
 * @since 2026/10/4
 */
public record ValueRef(Bag bag, String path) {

    /**
     * 契约 {@code from} 前缀对应的数据袋。
     */
    public enum Bag {
        /**
         * 节点输入。
         */
        INPUTS("inputs"),

        /**
         * 节点输出。
         */
        OUTPUTS("outputs"),

        /**
         * 任务级共享值。
         */
        SHARED("shared"),

        /**
         * 会话级共享值。
         */
        SESSION("session"),

        /**
         * 任务前情。
         */
        PRIOR("prior"),

        /**
         * 平台身份。
         */
        PLATFORM("platform"),

        /**
         * 成功证据抽取。
         */
        EVIDENCE("evidence");

        private final String wirePrefix;

        Bag(String wirePrefix) {
            this.wirePrefix = wirePrefix;
        }

        /**
         * @return JSON / WorkingTree 下的袋名
         */
        public String wirePrefix() {
            return wirePrefix;
        }
    }

    /**
     * 解析 {@code inputs.x}、{@code evidence.key.path} 等表达式。
     *
     * @param from 契约 from 原文
     * @return 解析结果
     */
    public static ValueRef parse(String from) {
        String trimmed = Strings.trim(from);
        if (Strings.isBlank(trimmed)) {
            throw new BusinessException(RuntimeErrorCode.ILLEGAL_SOURCE, from);
        }
        // evidence 前缀单独处理：其余路径可能含点号，不能按第一段切
        if (trimmed.startsWith(Bag.EVIDENCE.wirePrefix() + ".")) {
            String rest = trimmed.substring(Bag.EVIDENCE.wirePrefix().length() + 1);
            if (Strings.isBlank(rest)) {
                throw new BusinessException(RuntimeErrorCode.ILLEGAL_SOURCE, from);
            }
            return new ValueRef(Bag.EVIDENCE, rest);
        }
        for (Bag bag : Bag.values()) {
            // evidence 已在上方按完整 remainder 解析
            if (bag == Bag.EVIDENCE) {
                continue;
            }
            String prefix = bag.wirePrefix() + ".";
            if (trimmed.startsWith(prefix)) {
                String rest = trimmed.substring(prefix.length());
                if (Strings.isBlank(rest)) {
                    throw new BusinessException(RuntimeErrorCode.ILLEGAL_SOURCE, from);
                }
                return new ValueRef(bag, rest);
            }
        }
        throw new BusinessException(RuntimeErrorCode.ILLEGAL_SOURCE, from);
    }

    /**
     * 判断表达式是否为已知 {@code from} 前缀，不抛异常。
     *
     * @param from 契约 from 文本；可空
     * @return 能被 {@link #parse(String)} 识别时为 {@code true}
     */
    public static boolean isKnown(@Nullable String from) {
        if (Strings.isBlank(from)) {
            return false;
        }
        try {
            parse(from);
            return true;
        } catch (BusinessException _) {
            // 非法前缀只表示「不是已知 from」，调用方据此走常量或其它路径
            return false;
        }
    }
}
