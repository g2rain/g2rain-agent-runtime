package com.g2rain.agent.runtime.domain.hydrate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Skill 的运行时契约。
 *
 * <p>契约把规划模式、输入绑定、输出约束、完成条件和工具用法集中描述。运行时通过默认
 * 访问方法解释可选字段，通过装载期字段 {@link #doneWhenExpr} 避免在每次节点执行时重复
 * 解析完成条件文本。</p>
 *
 * @author alpha
 * @since 2026/9/10
 */
@Getter
@Setter
@NoArgsConstructor
public class SkillContract {

    /**
     * 出图模式；缺省 REACT。
     */
    private @Nullable ExecutionMode mode;

    /**
     * 是否可委派；{@code null} 视为不可委派。
     */
    private @Nullable Boolean delegatable;

    /**
     * 节点开始时绑定的输入。
     */
    private Map<String, InputSpec> inputs = new HashMap<>();

    /**
     * 交接 / 共享 / 沉淀的输出。
     */
    private Map<String, OutputSpec> outputs = new HashMap<>();

    /**
     * 主业务工具的注册名称。
     */
    private @Nullable String target;

    /**
     * 完成条件谓词原文。
     */
    private @Nullable String doneWhen;

    /**
     * 装载期由 {@link #doneWhen} 解析；不参与 JSON 序列化。
     */
    @JsonIgnore
    private @Nullable DoneWhenExpr doneWhenExpr;

    /**
     * 工具注册名到工具用法的映射；只列出当前 Skill 实际允许调用的工具。
     */
    private Map<String, ToolSpec> tools = new HashMap<>();

    /**
     * @return 显式 true 才可委派
     */
    public boolean isDelegatable() {
        return Objects.requireNonNullElse(delegatable, false);
    }

    /**
     * @return 缺省 REACT
     */
    public ExecutionMode modeOrDefault() {
        return Objects.requireNonNullElse(mode, ExecutionMode.REACT);
    }

    /**
     * 按注册名称取得工具契约。
     *
     * @param toolName 工具注册名称；可空
     * @return 对应工具契约；名称为空或未声明时为 {@code null}
     */
    public @Nullable ToolSpec tool(@Nullable String toolName) {
        // 空名或未声明任何工具时没有契约可取
        if (Objects.isNull(toolName) || tools.isEmpty()) {
            return null;
        }

        return tools.get(toolName);
    }
}
