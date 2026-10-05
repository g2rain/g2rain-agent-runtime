package com.g2rain.agent.runtime.domain.hydrate;


import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把完成条件文本解析成 {@link DoneWhenExpr}。
 * <p>
 * OR 优先于 AND；原子条款无法识别时标为 {@link DoneWhenKind#UNKNOWN}。
 * </p>
 *
 * @author alpha
 * @since 2026/10/4
 */
public final class DoneWhenParser {

    private static final Pattern OUTPUT_LENGTH_GTE = Pattern.compile(
        "^outputs\\.([a-zA-Z][a-zA-Z0-9_]*)\\s+length\\s*>=\\s*(\\d+)$",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern OUTPUT_ITEM_FIELD_EXISTS = Pattern.compile(
        "^outputs\\.([a-zA-Z][a-zA-Z0-9_]*)\\[\\*]\\.([a-zA-Z][a-zA-Z0-9_]*)\\s+exists$",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern OUTPUT_FIELD_EXISTS = Pattern.compile(
        "^outputs\\.([a-zA-Z][a-zA-Z0-9_]*)\\s+exists$", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TARGET_TOOL_SUCCEEDED = Pattern.compile(
        "^target\\s+succeeded$", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EVIDENCE_TOOL_SUCCEEDED = Pattern.compile(
        "^evidence\\.([a-zA-Z0-9_:.-]+)\\s+succeeded$", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EVIDENCE_KEY_EXISTS = Pattern.compile(
        "^evidence\\.([a-zA-Z][a-zA-Z0-9_]*)\\s+exists$", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TOOL_SUCCEEDED = Pattern.compile(
        "^([a-zA-Z0-9_:.-]+)\\s+succeeded$", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TOOL_EMPTY = Pattern.compile(
        "^([a-zA-Z0-9_:.-]+)\\s+empty$", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern AND_SPLIT = Pattern.compile("\\s+AND\\s+", Pattern.CASE_INSENSITIVE);

    private static final Pattern OR_SPLIT = Pattern.compile("\\s+OR\\s+", Pattern.CASE_INSENSITIVE);

    private static final Pattern BARE_ENTITY_FIELD_EXISTS = Pattern.compile(
        "^([a-zA-Z][a-zA-Z0-9_]*)\\.([a-zA-Z][a-zA-Z0-9_]*)\\s+exists$"
    );

    /**
     * 静态解析器，禁止实例化。
     */
    private DoneWhenParser() {

    }

    /**
     * 将完成条件文本解析为不可变表达式树。
     *
     * <p>解析顺序为 OR、AND、原子条件；因此 {@code A OR B AND C} 会被解释为
     * {@code A OR (B AND C)}。空白输入不构造表达式。</p>
     *
     * @param expression 完成条件文本；可空或空白
     * @return 表达式树；输入为空时返回 {@code null}
     * @apiNote {@code DoneWhenParser.parseExpression("target succeeded AND outputs.items exists")}
     * 会生成一个包含两个原子条件的 AND 节点。
     */
    public static @Nullable DoneWhenExpr parseExpression(@Nullable String expression) {
        // 空白原文没有完成条件，避免构造空树
        if (Objects.isNull(expression) || expression.isBlank()) {
            return null;
        }

        return parseComposite(expression.trim());
    }

    /**
     * 递归检查表达式树中是否存在无法识别的原子条件。
     *
     * @param expr 待检查表达式；可空
     * @return 包含 UNKNOWN 原子时返回 {@code true}
     */
    public static boolean hasUnknown(@Nullable DoneWhenExpr expr) {
        return switch (expr) {
            case null -> false;
            case DoneWhenExpr.Or(var children) -> children.stream().anyMatch(DoneWhenParser::hasUnknown);
            case DoneWhenExpr.And(var children) -> children.stream().anyMatch(DoneWhenParser::hasUnknown);
            case DoneWhenExpr.Atom atom -> atom.kind() == DoneWhenKind.UNKNOWN;
        };
    }

    /**
     * 检查完成条件文本是否包含无法识别的原子条件。
     *
     * @param expression 完成条件文本；可空
     * @return 包含未知原子或无法解析时返回 {@code true}
     */
    public static boolean containsUnknownAtom(@Nullable String expression) {
        return hasUnknown(parseExpression(expression));
    }

    /**
     * 按 OR、AND 的优先顺序拆分复合表达式。
     *
     * @param expr 已去除首尾空白的表达式
     * @return 复合表达式树或单个原子节点
     */
    private static DoneWhenExpr parseComposite(String expr) {
        // OR 先于 AND，所以 A OR B AND C 会落成 A OR (B AND C)
        if (OR_SPLIT.matcher(expr).find()) {
            List<DoneWhenExpr> children = new ArrayList<>();
            for (String part : OR_SPLIT.split(expr)) {
                children.add(parseComposite(part.trim()));
            }
            return new DoneWhenExpr.Or(List.copyOf(children));
        }

        if (AND_SPLIT.matcher(expr).find()) {
            List<DoneWhenExpr> children = new ArrayList<>();
            for (String part : AND_SPLIT.split(expr)) {
                children.add(parseComposite(part.trim()));
            }
            return new DoneWhenExpr.And(List.copyOf(children));
        }

        return parseAtom(expr);
    }

    /**
     * 解析一条原子条款。
     *
     * @param clause 单条完成条件文本；可空或空白
     * @return 原子节点；无法识别时 {@code kind} 为 {@link DoneWhenKind#UNKNOWN}
     */
    public static DoneWhenExpr.Atom parseAtom(@Nullable String clause) {
        // 空条款无法求值，标 UNKNOWN 让装载期拒收
        if (Objects.isNull(clause)) {
            return new DoneWhenExpr.Atom(DoneWhenKind.UNKNOWN, null, null, "");
        }

        String trimmed = clause.trim();
        if (trimmed.isEmpty()) {
            return new DoneWhenExpr.Atom(DoneWhenKind.UNKNOWN, null, null, trimmed);
        }

        Matcher outputLength = OUTPUT_LENGTH_GTE.matcher(trimmed);
        if (outputLength.matches()) {
            return new DoneWhenExpr.Atom(
                DoneWhenKind.OUTPUT_LENGTH_GTE,
                null,
                outputLength.group(1),
                trimmed,
                Integer.parseInt(outputLength.group(2))
            );
        }

        Matcher outputItem = OUTPUT_ITEM_FIELD_EXISTS.matcher(trimmed);
        if (outputItem.matches()) {
            return new DoneWhenExpr.Atom(
                DoneWhenKind.OUTPUT_ITEM_FIELD_EXISTS,
                outputItem.group(1),
                outputItem.group(2),
                trimmed
            );
        }

        Matcher outputField = OUTPUT_FIELD_EXISTS.matcher(trimmed);
        if (outputField.matches()) {
            return new DoneWhenExpr.Atom(DoneWhenKind.OUTPUT_FIELD_EXISTS, null, outputField.group(1), trimmed);
        }

        if (TARGET_TOOL_SUCCEEDED.matcher(trimmed).matches()) {
            return new DoneWhenExpr.Atom(DoneWhenKind.TARGET_TOOL_SUCCEEDED, null, null, trimmed);
        }

        Matcher evidenceTool = EVIDENCE_TOOL_SUCCEEDED.matcher(trimmed);
        if (evidenceTool.matches()) {
            return new DoneWhenExpr.Atom(DoneWhenKind.EVIDENCE_TOOL_SUCCEEDED, evidenceTool.group(1), null, trimmed);
        }

        Matcher evidenceKey = EVIDENCE_KEY_EXISTS.matcher(trimmed);
        if (evidenceKey.matches()) {
            return new DoneWhenExpr.Atom(DoneWhenKind.EVIDENCE_KEY_EXISTS, null, evidenceKey.group(1), trimmed);
        }

        // empty 须先于 succeeded，避免 "x empty" 被通用 succeeded 形态吞掉
        Matcher toolEmpty = TOOL_EMPTY.matcher(trimmed);
        if (toolEmpty.matches()) {
            return new DoneWhenExpr.Atom(DoneWhenKind.TOOL_EMPTY, toolEmpty.group(1), null, trimmed);
        }

        Matcher toolSucceeded = TOOL_SUCCEEDED.matcher(trimmed);
        if (toolSucceeded.matches()) {
            return new DoneWhenExpr.Atom(DoneWhenKind.TOOL_SUCCEEDED, toolSucceeded.group(1), null, trimmed);
        }

        Matcher bare = BARE_ENTITY_FIELD_EXISTS.matcher(trimmed);
        if (bare.matches()) {
            String entity = bare.group(1);
            // outputs.x exists 已有专形；裸实体字段不升格，标 UNKNOWN 以免误当完成条件
            if ("outputs".equalsIgnoreCase(entity)) {
                return new DoneWhenExpr.Atom(DoneWhenKind.OUTPUT_FIELD_EXISTS, null, bare.group(2), trimmed);
            }

            return new DoneWhenExpr.Atom(DoneWhenKind.UNKNOWN, entity, bare.group(2), trimmed);
        }

        // 无一形态命中：保留原文，标 UNKNOWN
        return new DoneWhenExpr.Atom(DoneWhenKind.UNKNOWN, null, null, trimmed);
    }
}
