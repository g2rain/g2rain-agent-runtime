/**
 * Model（模型网关）包：Runtime 访问大模型服务的出站契约与默认实现。
 * <p>
 * 提供 Chat Completions 与 Embeddings 两类调用入口，由 {@link com.g2rain.agent.runtime.engine.model.ModelGateway} 定义、
 * {@link com.g2rain.agent.runtime.engine.model.DefaultModelGateway} 经 HTTP 转发到配置的模型服务。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.model;

import org.jspecify.annotations.NullMarked;
