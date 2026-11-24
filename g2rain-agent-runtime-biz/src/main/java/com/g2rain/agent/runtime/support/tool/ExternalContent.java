package com.g2rain.agent.runtime.support.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部内容标记：把成功结果文本包裹为带工具名的标签，并转义正文中的闭合标记。
 *
 * <pre>{@code
 * String marked = ExternalContent.wrap("sample_tool", "hello");
 * }</pre>
 *
 * @author alpha
 * @since 2026/11/24
 */
public final class ExternalContent {

    /**
     * 外部内容标签名。
     */
    public static final String TAG = "external_content";

    /**
     * 工具名属性名。
     */
    public static final String ATTR_TOOL = "tool";

    private static final String OPEN_PREFIX = "<" + TAG + " " + ATTR_TOOL + "=\"";

    private static final String OPEN_SUFFIX = "\">\n";

    private static final String CLOSE = "</" + TAG + ">";

    private static final String ESCAPED_CLOSE = "<\\/" + TAG + ">";

    private static final Pattern CLOSE_TAG = Pattern.compile("</\\s*" + TAG + "\\s*>", Pattern.CASE_INSENSITIVE);

    private ExternalContent() {

    }

    /**
     * 按约定包裹并转义闭合标记。
     *
     * @param toolName 工具名
     * @param text     结果正文
     * @return 包裹后的文本
     */
    public static String wrap(String toolName, String text) {
        return OPEN_PREFIX
            + toolName
            + OPEN_SUFFIX
            + CLOSE_TAG.matcher(text).replaceAll(Matcher.quoteReplacement(ESCAPED_CLOSE))
            + "\n"
            + CLOSE;
    }
}
