package com.g2rain.agent.runtime.service.impl;

import com.g2rain.common.exception.SystemErrorCode;
import com.g2rain.common.id.IdGenerator;
import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.utils.Asserts;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.validation.Validations;
import com.g2rain.agent.runtime.converter.ChatMessageConverter;
import com.g2rain.agent.runtime.dao.ChatMessageDao;
import com.g2rain.agent.runtime.dao.po.ChatMessagePo;
import com.g2rain.agent.runtime.dto.ChatMessageDto;
import com.g2rain.agent.runtime.dto.ChatMessageSelectDto;
import com.g2rain.agent.runtime.service.ChatMessageService;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import com.g2rain.common.web.PrincipalContextHolder;
import com.g2rain.mybatis.pagination.PageContext;
import com.g2rain.mybatis.pagination.model.Page;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 聊天消息表服务实现类
 * 表名: chat_message
 *
 * @author G2rain Generator
 */
@Service(value = "chatMessageServiceImpl")
public class ChatMessageServiceImpl implements ChatMessageService {

    @Resource(name = "chatMessageDao")
    private ChatMessageDao chatMessageDao;

    private IdGenerator idGenerator;

    @Qualifier("idGenerator")
    @Autowired(required = false)
    public void setIdGenerator(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public List<ChatMessageVo> selectList(ChatMessageSelectDto selectDto) {
        return chatMessageDao.selectList(selectDto)
            .stream()
            .map(ChatMessageConverter.INSTANCE::po2vo)
            .toList();
    }

    @Override
    public PageData<ChatMessageVo> selectPage(PageSelectListDto<ChatMessageSelectDto> selectDto) {
        Page<ChatMessagePo> page = PageContext.of(selectDto.getPageNum(), selectDto.getPageSize(), () -> {
            chatMessageDao.selectList(selectDto.getQuery());
        });
        List<ChatMessageVo> result = page.getResult()
            .stream()
            .map(ChatMessageConverter.INSTANCE::po2vo)
            .toList();
        return PageData.of(page.getPageNum(), page.getPageSize(), page.getTotal(), result);
    }

    @Override
    public Long save(ChatMessageDto dto) {
        if (Objects.isNull(dto.getOrganId())) {
            dto.setOrganId(PrincipalContextHolder.getOrganId());
        }

        Validations.validateSave(dto);

        // 转换 DTO 为 PO
        ChatMessagePo entity = ChatMessageConverter.INSTANCE.dto2po(dto);

        // 判断是新增还是更新
        Long id = entity.getId();
        if (Objects.isNull(id) || id == 0) {
            // 新增：使用IdGenerator生成主键
            entity.setId(idGenerator.generateId());
            LocalDateTime now = Moments.now();
            entity.setUpdateTime(now);
            entity.setCreateTime(now);
            int success = chatMessageDao.insert(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.CREATE_DATA_ERROR);
        } else {
            ChatMessagePo existing = chatMessageDao.selectById(id);
            Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
            // 更新：直接更新
            entity.setUpdateTime(Moments.now());
            int success = chatMessageDao.update(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.UPDATE_DATA_ERROR, id);
        }

        return entity.getId();
    }

    @Override
    public int delete(Long id) {
        ChatMessagePo existing = chatMessageDao.selectById(id);
        Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
        return chatMessageDao.delete(id);
    }
}
