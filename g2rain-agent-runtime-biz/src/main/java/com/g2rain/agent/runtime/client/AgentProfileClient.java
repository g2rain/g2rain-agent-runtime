package com.g2rain.agent.runtime.client;


import com.g2rain.agent.studio.api.AgentProfileApi;
import org.springframework.cloud.openfeign.FeignClient;

/**
 * AgentProfileClient：Studio Agent 档案 Feign 客户端。
 * <p>
 * Turn（本轮）= 一次用户请求从进入到产出结果的完整处理。
 * 契约继承 {@link AgentProfileApi}；服务名 g2rain-agent-studio，路径 /agent_profile。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@FeignClient(name = "g2rain-agent-studio", contextId = "agentProfileClient", path = "/agent_profile")
public interface AgentProfileClient extends AgentProfileApi {

}
