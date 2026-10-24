package com.g2rain.agent.runtime.service.impl;

import com.g2rain.common.exception.SystemErrorCode;
import com.g2rain.common.id.IdGenerator;
import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.utils.Asserts;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.validation.Validations;
import com.g2rain.agent.runtime.converter.ChatProgressConverter;
import com.g2rain.agent.runtime.dao.ChatProgressDao;
import com.g2rain.agent.runtime.dao.po.ChatProgressPo;
import com.g2rain.agent.runtime.dto.ChatProgressDto;
import com.g2rain.agent.runtime.dto.ChatProgressSelectDto;
import com.g2rain.agent.runtime.service.ChatProgressService;
import com.g2rain.agent.runtime.vo.ChatProgressVo;
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
 * 执行过程步骤表服务实现类
 * 表名: chat_progress
 *
 * @author G2rain Generator
 */
@Service(value = "chatProgressServiceImpl")
public class ChatProgressServiceImpl implements ChatProgressService {

    @Resource(name = "chatProgressDao")
    private ChatProgressDao chatProgressDao;

    private IdGenerator idGenerator;

    @Qualifier("idGenerator")
    @Autowired(required = false)
    public void setIdGenerator(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public List<ChatProgressVo> selectList(ChatProgressSelectDto selectDto) {
        return chatProgressDao.selectList(selectDto)
            .stream()
            .map(ChatProgressConverter.INSTANCE::po2vo)
            .toList();
    }

    @Override
    public PageData<ChatProgressVo> selectPage(PageSelectListDto<ChatProgressSelectDto> selectDto) {
        Page<ChatProgressPo> page = PageContext.of(selectDto.getPageNum(), selectDto.getPageSize(), () -> {
            chatProgressDao.selectList(selectDto.getQuery());
        });
        List<ChatProgressVo> result = page.getResult()
            .stream()
            .map(ChatProgressConverter.INSTANCE::po2vo)
            .toList();
        return PageData.of(page.getPageNum(), page.getPageSize(), page.getTotal(), result);
    }

    @Override
    public Long save(ChatProgressDto dto) {
        Validations.validateSave(dto);

        // 转换 DTO 为 PO
        ChatProgressPo entity = ChatProgressConverter.INSTANCE.dto2po(dto);

        // 判断是新增还是更新
        Long id = entity.getId();
        if (Objects.isNull(id) || id == 0) {
            // 新增：使用IdGenerator生成主键
            entity.setId(idGenerator.generateId());
            LocalDateTime now = Moments.now();
            entity.setUpdateTime(now);
            entity.setCreateTime(now);
            int success = chatProgressDao.insert(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.CREATE_DATA_ERROR);
        } else {
            ChatProgressPo existing = chatProgressDao.selectById(id);
            Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
            // 更新：直接更新
            entity.setUpdateTime(Moments.now());
            int success = chatProgressDao.update(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.UPDATE_DATA_ERROR, id);
        }

        return entity.getId();
    }

    @Override
    public int delete(Long id) {
        ChatProgressPo existing = chatProgressDao.selectById(id);
        Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
        return chatProgressDao.delete(id);
    }

    @Override
    public int saveBatch(List<ChatProgressDto> list) {
        if (Collections.isEmpty(list)) {
            return 0;
        }

        LocalDateTime now = Moments.now();
        List<ChatProgressPo> entities = list.stream().map(dto -> {
            ChatProgressPo entity = ChatProgressConverter.INSTANCE.dto2po(dto);
            entity.setId(idGenerator.generateId());
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            return entity;
        }).toList();
        int success = chatProgressDao.insertMultiple(entities);
        Asserts.greaterThan(success, 0, SystemErrorCode.CREATE_DATA_ERROR);
        return success;
    }
}
