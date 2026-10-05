package com.g2rain.agent.runtime.service.impl;

import com.g2rain.common.exception.SystemErrorCode;
import com.g2rain.common.id.IdGenerator;
import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.utils.Asserts;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.validation.Validations;
import com.g2rain.agent.runtime.converter.ToolCallRecordConverter;
import com.g2rain.agent.runtime.dao.ToolCallRecordDao;
import com.g2rain.agent.runtime.dao.po.ToolCallRecordPo;
import com.g2rain.agent.runtime.dto.ToolCallRecordDto;
import com.g2rain.agent.runtime.dto.ToolCallRecordSelectDto;
import com.g2rain.agent.runtime.service.ToolCallRecordService;
import com.g2rain.agent.runtime.vo.ToolCallRecordVo;
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
 * 工具调用记录表服务实现类
 * 表名: tool_call_record
 *
 * @author G2rain Generator
 */
@Service(value = "toolCallRecordServiceImpl")
public class ToolCallRecordServiceImpl implements ToolCallRecordService {

    @Resource(name = "toolCallRecordDao")
    private ToolCallRecordDao toolCallRecordDao;

    private IdGenerator idGenerator;

    @Qualifier("idGenerator")
    @Autowired(required = false)
    public void setIdGenerator(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public List<ToolCallRecordVo> selectList(ToolCallRecordSelectDto selectDto) {
        return toolCallRecordDao.selectList(selectDto)
            .stream()
            .map(ToolCallRecordConverter.INSTANCE::po2vo)
            .toList();
    }

    @Override
    public PageData<ToolCallRecordVo> selectPage(PageSelectListDto<ToolCallRecordSelectDto> selectDto) {
        Page<ToolCallRecordPo> page = PageContext.of(selectDto.getPageNum(), selectDto.getPageSize(), () -> {
            toolCallRecordDao.selectList(selectDto.getQuery());
        });
        List<ToolCallRecordVo> result = page.getResult()
            .stream()
            .map(ToolCallRecordConverter.INSTANCE::po2vo)
            .toList();
        return PageData.of(page.getPageNum(), page.getPageSize(), page.getTotal(), result);
    }

    @Override
    public Long save(ToolCallRecordDto dto) {
        if (Objects.isNull(dto.getOrganId())) {
            dto.setOrganId(PrincipalContextHolder.getOrganId());
        }

        Validations.validateSave(dto);

        // 转换 DTO 为 PO
        ToolCallRecordPo entity = ToolCallRecordConverter.INSTANCE.dto2po(dto);

        // 判断是新增还是更新
        Long id = entity.getId();
        if (Objects.isNull(id) || id == 0) {
            // 新增：使用IdGenerator生成主键
            entity.setId(idGenerator.generateId());
            LocalDateTime now = Moments.now();
            entity.setUpdateTime(now);
            entity.setCreateTime(now);
            int success = toolCallRecordDao.insert(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.CREATE_DATA_ERROR);
        } else {
            ToolCallRecordPo existing = toolCallRecordDao.selectById(id);
            Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
            // 更新：直接更新
            entity.setUpdateTime(Moments.now());
            int success = toolCallRecordDao.update(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.UPDATE_DATA_ERROR, id);
        }

        return entity.getId();
    }

    @Override
    public int delete(Long id) {
        ToolCallRecordPo existing = toolCallRecordDao.selectById(id);
        Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
        return toolCallRecordDao.delete(id);
    }
}
