package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.SourceType;
import com.g2rain.agent.runtime.spi.CallView;
import com.g2rain.agent.runtime.spi.Denial;
import com.g2rain.agent.runtime.spi.Fact;
import com.g2rain.agent.runtime.spi.ToolGuard;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 附加检查门：依次调用全部 {@link ToolGuard}，首个拒绝即短路。
 *
 * <p>排在参数契约之后、重复调用熔断之前。没有任何实现、内置工具、无技能、
 * 或用户确认后执行的写调用时跳过。拦下发生在调用前，不登记证据、不触发审批。</p>
 *
 * @author alpha
 * @since 2026/11/17
 */
@Slf4j
@Component
@Order(4000)
@RequiredArgsConstructor
public class GuardInterceptor implements ToolInterceptor {

    /**
     * 调用前附加检查；没有实现时为空列表。
     */
    private final List<ToolGuard> guards;

    /**
     * 证据抽取：为本节点与上游构造成功事实。
     */
    private final EvidenceReader evidenceReader;

    /**
     * 调用前依次做附加检查；首个拒绝或抛异常时短路为拒绝观测。
     *
     * @param request 本次工具调用请求
     * @return 被拒观测；放行时为空
     */
    @Override
    public Optional<Observation> before(ToolInvokeRequest request) {
        if (guards.isEmpty() || ToolCallbacks.isBuiltin(request.toolCallback())) {
            return Optional.empty();
        }

        ToolExecutionContext context = request.context();
        String skillKey = context.getSkillKey();
        if (Strings.isBlank(skillKey) || context.approvedWrite()) {
            return Optional.empty();
        }

        CallView call = callViewOf(request, skillKey);
        for (ToolGuard guard : guards) {
            try {
                Optional<Denial> denial = guard.check(call);
                if (denial.isPresent()) {
                    Denial denied = denial.get();
                    traceDenied(skillKey, request.toolName(), call.pin(), denied.code());
                    return Optional.of(Observation.rejected(
                        RuntimeErrorCode.GUARD_DENIED, denied.message(), denied.nextStep()));
                }
            } catch (RuntimeException ex) {
                log.error("[GuardInterceptor] tool guard unavailable skillKey={} tool={}",
                    skillKey, request.toolName(), ex);
                traceDenied(skillKey, request.toolName(), call.pin(), RuntimeErrorCode.GUARD_UNAVAILABLE.name());
                return Optional.of(Observation.rejected(
                    RuntimeErrorCode.GUARD_UNAVAILABLE,
                    ModelHint.GUARD_UNAVAILABLE.text(),
                    ModelHint.NEXT_RETRY.text()
                ));
            }
        }

        return Optional.empty();
    }

    /**
     * 组装只读调用视图：参数与来源为副本，facts 含本节点与上游抽取。
     *
     * @param request  本次请求
     * @param skillKey 当前技能键（已非空白）
     * @return 调用视图
     */
    private CallView callViewOf(ToolInvokeRequest request, String skillKey) {
        ToolExecutionContext context = request.context();
        JsonNode arguments = request.args().deepCopy();
        Map<String, SourceType> sources = new LinkedHashMap<>();
        for (Map.Entry<String, Source> entry : request.argSources().entrySet()) {
            sources.put(entry.getKey(), entry.getValue().type());
        }

        List<Fact> facts = evidenceReader.nodeExtracts(context).stream()
            .map(extract -> new Fact(extract.tool(), extract.key(), extract.value()))
            .toList();
        Skill skill = context.getSkill();
        String pin = Objects.isNull(skill) ? null : skill.pin();
        return new CallView(skillKey, request.toolName(), arguments, sources, facts, pin);
    }

    /**
     * 输出拦下轨迹：不含参数原值。
     *
     * @param skillKey 技能键
     * @param tool     工具名
     * @param pin      版本标记；可空
     * @param code     拒绝码或 {@code GUARD_UNAVAILABLE}
     */
    private static void traceDenied(String skillKey, String tool, @Nullable String pin, String code) {
        Trace.at(TraceStage.TOOL, "guard_denied")
            .kv("skillKey", skillKey)
            .kv("tool", tool)
            .kv("pin", pin)
            .kv("code", code)
            .warn();
    }
}
