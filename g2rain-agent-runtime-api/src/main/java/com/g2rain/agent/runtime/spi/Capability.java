package com.g2rain.agent.runtime.spi;

/**
 * 能力工具：插件为数字员工提供的、与具体业务系统无关的工具。
 *
 * <p>安装插件后对所有节点可见，与内置工具相同。没有任何实现时 Runtime 行为不变。</p>
 *
 * @author alpha
 * @since 2026/11/24
 */
public interface Capability {

    /**
     * 工具说明，Runtime 启动时读取一次。
     *
     * @return 能力说明
     */
    CapabilitySpec spec();

    /**
     * 执行一次调用；业务失败返回 {@link CapabilityResult#failure}，不抛异常。
     *
     * @param call 入参与调用现场
     * @return 成功或失败结果
     */
    CapabilityResult call(CapabilityCall call);
}
