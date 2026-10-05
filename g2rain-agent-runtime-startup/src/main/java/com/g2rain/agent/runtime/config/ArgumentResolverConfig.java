package com.g2rain.agent.runtime.config;


import com.g2rain.common.model.PageSelectListDto;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ArgumentResolverConfig（Web 参数解析器配置）= 注册自定义 Spring MVC 参数解析器，自动绑定 {@link PageSelectListDto} 分页查询入参。
 *
 * <p>Pipeline：HTTP 请求带 {@code pageNum}/{@code pageSize} 与 {@code query.*} 业务字段 → 解析器写 {@link PageSelectListDto} → Controller 只读使用。
 *
 * <p>例：{@code GET /routes?pageNum=1&pageSize=20&query.name=foo} 绑定为 {@code PageSelectListDto<RoutesQueryDto>}，query 对象 {@code name=foo}。
 *
 * @author alpha
 * @since 2026/8/21
 */
@Configuration
public class ArgumentResolverConfig implements WebMvcConfigurer {

    /**
     * 注册自定义参数解析器
     *
     * <p>当控制器方法参数类型为 {@link PageSelectListDto} 时,
     * 使用匿名 {@link HandlerMethodArgumentResolver} 完成参数绑定</p>
     *
     * @param resolvers 解析器列表
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {

            /**
             * 判断方法参数是否支持解析
             *
             * @param parameter 方法参数
             * @return true 支持 PageSelectListDto 类型参数
             */
            @Override
            public boolean supportsParameter(@NonNull MethodParameter parameter) {
                return PageSelectListDto.class.isAssignableFrom(parameter.getParameterType());
            }

            /**
             * 解析 PageSelectListDto 参数
             *
             * <p>功能：</p>
             * <ul>
             *   <li>创建 PageSelectListDto 实例</li>
             *   <li>根据泛型动态创建 query 对象</li>
             *   <li>绑定分页参数 pageNum / pageSize</li>
             *   <li>绑定 query 的业务字段，自动去掉 query. 前缀</li>
             * </ul>
             *
             * @param parameter 方法参数
             * @param mavContainer MVC 容器
             * @param webRequest 当前请求对象
             * @param binderFactory 数据绑定工厂
             * @return 完整填充的 PageSelectListDto 对象
             * @throws Exception 绑定或实例化失败时抛出
             */
            @Override
            public Object resolveArgument(@NonNull MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          @NonNull NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) throws Exception {

                // 1️⃣ 创建 PageSelectListDto 对象
                PageSelectListDto<Object> pageRequest = new PageSelectListDto<>();
                // 2️⃣ 获取泛型 T
                Class<?> queryClass = extractQueryClass(parameter);
                // 3️⃣ 创建 query 对象
                Object query = queryClass.getDeclaredConstructor().newInstance();
                pageRequest.setQuery(query);

                // 4️⃣ 绑定分页参数 pageNum / pageSize
                WebDataBinder pageBinder = binderFactory.createBinder(webRequest, pageRequest, "pageSelectWebDataBinder");
                pageBinder.bind(new MutablePropertyValues(webRequest.getParameterMap()));

                // 5️⃣ 绑定 query 的业务字段，带 query 前缀
                WebDataBinder queryBinder = binderFactory.createBinder(webRequest, query, "bizSelectWebDataBinder");
                Map<String, String[]> parameters = webRequest.getParameterMap().entrySet().stream().collect(Collectors.toMap(
                    e -> e.getKey().replaceFirst("^query\\.", ""), Map.Entry::getValue
                ));

                queryBinder.bind(new MutablePropertyValues(parameters));

                return pageRequest;
            }

            /**
             * 提取 PageSelectListDto 的泛型类型
             *
             * @param parameter 方法参数
             * @return 泛型 Class 对象
             * @throws IllegalStateException 如果泛型未指定或数量异常
             */
            private Class<?> extractQueryClass(MethodParameter parameter) {
                Type genericType = parameter.getGenericParameterType();
                if (!(genericType instanceof ParameterizedType paramType)) {
                    throw new IllegalStateException("PageSelectListDto 必须有泛型参数");
                }

                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length != 1) {
                    throw new IllegalStateException("PageSelectListDto 泛型数量异常");
                }

                return (Class<?>) typeArgs[0];
            }
        });
    }
}
