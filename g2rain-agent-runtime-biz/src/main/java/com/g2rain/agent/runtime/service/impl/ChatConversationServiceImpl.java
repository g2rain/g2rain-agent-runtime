package com.g2rain.agent.runtime.service.impl;

import com.g2rain.common.exception.SystemErrorCode;
import com.g2rain.common.id.IdGenerator;
import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.utils.Asserts;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.validation.Validations;
import com.g2rain.agent.runtime.converter.ChatConversationConverter;
import com.g2rain.agent.runtime.dao.ChatConversationDao;
import com.g2rain.agent.runtime.dao.TaskInstanceDao;
import com.g2rain.agent.runtime.dao.po.ChatConversationPo;
import com.g2rain.agent.runtime.dao.po.TaskInstancePo;
import com.g2rain.agent.runtime.dto.ChatConversationDto;
import com.g2rain.agent.runtime.dto.ChatConversationSelectDto;
import com.g2rain.agent.runtime.dto.TaskInstanceSelectDto;
import com.g2rain.agent.runtime.service.ChatConversationService;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
import com.g2rain.common.web.PrincipalContextHolder;
import com.g2rain.mybatis.pagination.PageContext;
import com.g2rain.mybatis.pagination.model.Page;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 聊天会话表服务实现类
 * 表名: chat_conversation
 *
 * @author G2rain Generator
 */
@Service(value = "chatConversationServiceImpl")
public class ChatConversationServiceImpl implements ChatConversationService {

    @Resource(name = "chatConversationDao")
    private ChatConversationDao chatConversationDao;

    @Resource(name = "taskInstanceDao")
    private TaskInstanceDao taskInstanceDao;

    private IdGenerator idGenerator;

    @Qualifier("idGenerator")
    @Autowired(required = false)
    public void setIdGenerator(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public List<ChatConversationVo> selectList(ChatConversationSelectDto selectDto) {
        return chatConversationDao.selectList(selectDto)
            .stream()
            .map(ChatConversationConverter.INSTANCE::po2vo)
            .toList();
    }

    @Override
    public PageData<ChatConversationVo> selectPage(PageSelectListDto<ChatConversationSelectDto> selectDto) {
        PageData<ChatConversationVo> page = selectSummaryPage(selectDto);
        fillActiveTaskStatus(page.getRecords());
        return page;
    }

    /**
     * 按当前页的 activeTaskId 批量查任务摘要，回填当前任务状态。
     */
    private void fillActiveTaskStatus(List<ChatConversationVo> rows) {
        if (Objects.isNull(rows) || rows.isEmpty()) {
            return;
        }

        List<String> taskIds = rows.stream()
            .map(ChatConversationVo::getActiveTaskId)
            .filter(Strings::isNotBlank)
            .distinct()
            .toList();
        if (taskIds.isEmpty()) {
            return;
        }

        TaskInstanceSelectDto select = new TaskInstanceSelectDto();
        select.setTaskIds(taskIds);
        Map<String, String> statusByTask = taskInstanceDao.selectSummaryList(select)
            .stream()
            .filter(task -> Objects.nonNull(task.getStatus()))
            .collect(Collectors.toMap(TaskInstancePo::getTaskId, TaskInstancePo::getStatus, (left, right) -> left));
        rows.forEach(row -> row.setActiveTaskStatus(statusByTask.get(row.getActiveTaskId())));
    }

    @Override
    public List<ChatConversationVo> selectSummaryList(ChatConversationSelectDto selectDto) {
        return chatConversationDao.selectSummaryList(selectDto)
            .stream()
            .map(ChatConversationConverter.INSTANCE::po2vo)
            .toList();
    }

    @Override
    public PageData<ChatConversationVo> selectSummaryPage(PageSelectListDto<ChatConversationSelectDto> selectDto) {
        Page<ChatConversationPo> page = PageContext.of(selectDto.getPageNum(), selectDto.getPageSize(), () -> {
            chatConversationDao.selectSummaryList(selectDto.getQuery());
        });
        List<ChatConversationVo> result = page.getResult()
            .stream()
            .map(ChatConversationConverter.INSTANCE::po2vo)
            .toList();
        return PageData.of(page.getPageNum(), page.getPageSize(), page.getTotal(), result);
    }

    @Override
    public Long save(ChatConversationDto dto) {
        if (Objects.isNull(dto.getOrganId())) {
            dto.setOrganId(PrincipalContextHolder.getOrganId());
        }

        if (Objects.isNull(dto.getUserId())) {
            dto.setUserId(PrincipalContextHolder.getUserId());
        }

        Validations.validateSave(dto);

        // 转换 DTO 为 PO
        ChatConversationPo entity = ChatConversationConverter.INSTANCE.dto2po(dto);

        // 判断是新增还是更新
        Long id = entity.getId();
        if (Objects.isNull(id) || id == 0) {
            // 新增：使用IdGenerator生成主键
            entity.setId(idGenerator.generateId());
            LocalDateTime now = Moments.now();
            entity.setUpdateTime(now);
            entity.setCreateTime(now);
            int success = chatConversationDao.insert(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.CREATE_DATA_ERROR);
        } else {
            ChatConversationPo existing = chatConversationDao.selectById(id);
            Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
            // 更新：直接更新
            entity.setUpdateTime(Moments.now());
            int success = chatConversationDao.update(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.UPDATE_DATA_ERROR, id);
        }

        return entity.getId();
    }

    @Override
    public int advanceStateVersion(String conversationId, long stateVersion, String sessionShared, String activeTaskId) {
        ChatConversationPo entity = new ChatConversationPo();
        entity.setOrganId(PrincipalContextHolder.getOrganId());
        entity.setConversationId(conversationId);
        entity.setStateVersion(stateVersion);
        entity.setSessionShared(sessionShared);
        entity.setActiveTaskId(activeTaskId);
        return chatConversationDao.advanceStateVersion(entity);
    }

    @Override
    public int delete(Long id) {
        ChatConversationPo existing = chatConversationDao.selectById(id);
        Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
        return chatConversationDao.delete(id);
    }
}
