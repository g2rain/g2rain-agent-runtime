package com.g2rain.agent.runtime.spi;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * {@link ToolGuard} 的检查结论：拒绝，或放行（可附带警告）。
 *
 * <pre>{@code
 * GuardVerdict.allow();
 * GuardVerdict.allow(List.of(new Notice("article-published-edit", "该文章已发布，修改会直接影响线上内容")));
 * GuardVerdict.deny(Denial.refuse("article-published-undeletable", "已发布的文章不能删除", "向用户说明原因"));
 * }</pre>
 *
 * @param denial  拒绝结果；放行时为 {@code null}
 * @param notices 放行时附带的警告；拒绝或无警告时为空列表
 * @author alpha
 * @since 2026/11/21
 */
public record GuardVerdict(@Nullable Denial denial, List<Notice> notices) {

    /**
     * 无警告的放行，供无须检查的调用复用。
     */
    private static final GuardVerdict ALLOW = new GuardVerdict(null, List.of());

    public GuardVerdict {
        notices = List.copyOf(notices);
    }

    /**
     * 放行，无警告。
     *
     * @return 放行结论
     */
    public static GuardVerdict allow() {
        return ALLOW;
    }

    /**
     * 放行并附带警告。
     *
     * @param notices 警告
     * @return 放行结论
     */
    public static GuardVerdict allow(List<Notice> notices) {
        return notices.isEmpty() ? ALLOW : new GuardVerdict(null, notices);
    }

    /**
     * 拒绝。
     *
     * @param denial 拒绝结果
     * @return 拒绝结论
     */
    public static GuardVerdict deny(Denial denial) {
        return new GuardVerdict(Objects.requireNonNull(denial, "denial"), List.of());
    }

    /**
     * 是否拒绝。
     *
     * @return 有拒绝结果时为 {@code true}
     */
    public boolean denied() {
        return Objects.nonNull(denial);
    }
}
