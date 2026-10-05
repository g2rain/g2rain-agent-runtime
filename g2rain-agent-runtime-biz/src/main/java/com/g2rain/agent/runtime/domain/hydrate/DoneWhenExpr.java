package com.g2rain.agent.runtime.domain.hydrate;


import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 完成条件的语法树。
 *
 * <p>表达式在契约装载时解析为本对象，运行时只根据节点输出和工具证据进行评估，
 * 从而避免在执行过程中重新解释原始文本。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
public sealed interface DoneWhenExpr {

    /**
     * 所有子条件都满足时成立。
     */
    record And(List<DoneWhenExpr> children) implements DoneWhenExpr {

    }

    /**
     * 任一子条件满足时成立。
     */
    record Or(List<DoneWhenExpr> children) implements DoneWhenExpr {

    }

    /**
     * 不可再拆分的完成条件，例如工具成功或输出字段存在。
     *
     * @param kind      种类
     * @param entity    工具名 / 集合名等；可空
     * @param field     字段名；可空
     * @param raw       原始条款
     * @param minLength 长度门槛；可空
     */
    record Atom(DoneWhenKind kind, @Nullable String entity, @Nullable String field, String raw,
                @Nullable Integer minLength) implements DoneWhenExpr {

        /**
         * 不带长度门槛的原子条款。
         *
         * @param kind   种类
         * @param entity 工具名 / 集合名等；可空
         * @param field  字段名；可空
         * @param raw    原始条款
         */
        public Atom(DoneWhenKind kind, @Nullable String entity, @Nullable String field, String raw) {
            this(kind, entity, field, raw, null);
        }
    }
}
