package com.g2rain.agent.runtime.support.tool;

import com.g2rain.agent.runtime.engine.tools.builtin.ControlBuiltinTools;
import com.g2rain.agent.runtime.engine.tools.builtin.EnvBuiltinTools;
import com.g2rain.agent.runtime.engine.tools.builtin.NodeBuiltinTools;
import com.g2rain.agent.runtime.engine.tools.builtin.SessionBuiltinTools;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 扫描内置工具类：每个 {@code @Tool} 方法都带 {@link BuiltinToolKind}，且 {@code step} 为合法 {@link RuntimeMessage}。
 *
 * @author alpha
 * @since 2026/11/23
 */
class BuiltinToolKindScanTest {

    private static final List<Class<?>> TOOL_CLASSES = List.of(
        ControlBuiltinTools.class,
        NodeBuiltinTools.class,
        SessionBuiltinTools.class,
        EnvBuiltinTools.class
    );

    private static final Set<RuntimeMessage> MESSAGES = EnumSet.allOf(RuntimeMessage.class);

    @Test
    void everyToolMethodHasBuiltinToolKindWithValidStep() {
        for (Class<?> type : TOOL_CLASSES) {
            Class<?> user = ClassUtils.getUserClass(type);
            for (Method method : user.getMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }

                BuiltinToolKind kind = method.getAnnotation(BuiltinToolKind.class);
                if (kind == null) {
                    fail(user.getSimpleName() + "#" + method.getName() + " missing @BuiltinToolKind");
                }

                assertNotNull(kind.step());
                assertTrue(MESSAGES.contains(kind.step()),
                    user.getSimpleName() + "#" + method.getName() + " step not in RuntimeMessage: " + kind.step());
            }
        }
    }
}
