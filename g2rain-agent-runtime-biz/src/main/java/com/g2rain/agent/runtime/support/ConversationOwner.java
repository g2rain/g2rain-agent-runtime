package com.g2rain.agent.runtime.support;

import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 当前请求可访问会话的属主范围。
 *
 * @author alpha
 * @since 2026/11/26
 */
public final class ConversationOwner {

    private final @Nullable Long organId;

    private final @Nullable Long userId;

    private final List<Long> userIds;

    /**
     * @param organId   当前机构
     * @param userId    当前用户
     * @param visitorId 当前访客
     */
    private ConversationOwner(@Nullable Long organId, @Nullable Long userId, @Nullable Long visitorId) {
        this.organId = organId;
        this.userId = userId;
        List<Long> ownerIds = new ArrayList<>();
        if (Objects.nonNull(userId)) {
            ownerIds.add(userId);
        }
        if (Objects.nonNull(visitorId) && !Objects.equals(visitorId, userId)) {
            ownerIds.add(visitorId);
        }
        this.userIds = List.copyOf(ownerIds);
    }

    /**
     * 从当前主体上下文构建会话属主范围。
     *
     * @return 当前机构及可访问会话的属主范围
     */
    public static ConversationOwner current() {
        return new ConversationOwner(
            PrincipalContextHolder.getOrganId(),
            PrincipalContextHolder.getUserId(),
            PrincipalContextHolder.getVisitorId()
        );
    }

    /**
     * 返回当前机构。
     *
     * @return 当前机构；未认证时为空
     */
    public @Nullable Long organId() {
        return organId;
    }

    /**
     * 返回当前用户。
     *
     * @return 当前用户；未认证时为空
     */
    public @Nullable Long userId() {
        return userId;
    }

    /**
     * 返回当前请求可访问会话的属主 ID。
     *
     * @return 当前用户 ID 和可选的访客 ID
     */
    public List<Long> userIds() {
        return userIds;
    }

    /**
     * 判断给定的会话属主是否属于当前请求。
     *
     * @param rowUserId 会话记录或热态中的属主 ID
     * @return 属主属于当前用户或访客时为 {@code true}
     */
    public boolean owns(@Nullable Long rowUserId) {
        return Objects.nonNull(rowUserId) && userIds.contains(rowUserId);
    }

    /**
     * 判断热态中的字符串属主是否属于当前请求。
     *
     * @param rowUserId 热态栅栏中的属主 ID
     * @return 属主属于当前用户或访客时为 {@code true}
     */
    public boolean owns(@Nullable String rowUserId) {
        if (Strings.isBlank(rowUserId)) {
            return false;
        }
        try {
            return owns(Long.valueOf(rowUserId));
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
