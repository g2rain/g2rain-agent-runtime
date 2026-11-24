package com.g2rain.agent.runtime.spi;

/**
 * 本轮回复的附件。
 *
 * @param type    附件类型
 * @param title   标题
 * @param content 正文
 * @author alpha
 * @since 2026/11/24
 */
public record Attachment(AttachmentType type, String title, String content) {

}
