package com.g2rain.agent.runtime.service.impl;

import com.g2rain.common.exception.SystemErrorCode;
import com.g2rain.common.id.IdGenerator;
import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.utils.Asserts;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.validation.Validations;
import com.g2rain.agent.runtime.converter.ToolBlockRecordConverter;
import com.g2rain.agent.runtime.dao.ToolBlockRecordDao;
import com.g2rain.agent.runtime.dao.po.ToolBlockRecordPo;
import com.g2rain.agent.runtime.dto.ToolBlockRecordDto;
import com.g2rain.agent.runtime.dto.ToolBlockRecordSelectDto;
import com.g2rain.agent.runtime.service.ToolBlockRecordService;
import com.g2rain.agent.runtime.vo.ToolBlockRecordVo;
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
 * 工具调用拦截记录表服务实现类
 * 表名: tool_block_record
 *
 * @author G2rain Generator
 */
@Service(value = "toolBlockRecordServiceImpl")
public class ToolBlockRecordServiceImpl implements ToolBlockRecordService {

    @Resource(name = "toolBlockRecordDao")
    private ToolBlockRecordDao toolBlockRecordDao;

    private IdGenerator idGenerator;

    @Qualifier("idGenerator")
    @Autowired(required = false)
    public void setIdGenerator(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public List<ToolBlockRecordVo> selectList(ToolBlockRecordSelectDto selectDto) {
        return toolBlockRecordDao.selectList(selectDto)
            .stream()
            .map(ToolBlockRecordConverter.INSTANCE::po2vo)
            .toList();
    }

    @Override
    public PageData<ToolBlockRecordVo> selectPage(PageSelectListDto<ToolBlockRecordSelectDto> selectDto) {
        Page<ToolBlockRecordPo> page = PageContext.of(selectDto.getPageNum(), selectDto.getPageSize(), () -> {
            toolBlockRecordDao.selectList(selectDto.getQuery());
        });
        List<ToolBlockRecordVo> result = page.getResult()
            .stream()
            .map(ToolBlockRecordConverter.INSTANCE::po2vo)
            .toList();
        return PageData.of(page.getPageNum(), page.getPageSize(), page.getTotal(), result);
    }

    @Override
    public Long save(ToolBlockRecordDto dto) {
        Validations.validateSave(dto);

        // 转换 DTO 为 PO
        ToolBlockRecordPo entity = ToolBlockRecordConverter.INSTANCE.dto2po(dto);

        // 判断是新增还是更新
        Long id = entity.getId();
        if (Objects.isNull(id) || id == 0) {
            // 新增：使用IdGenerator生成主键
            entity.setId(idGenerator.generateId());
            LocalDateTime now = Moments.now();
            entity.setUpdateTime(now);
            entity.setCreateTime(now);
            int success = toolBlockRecordDao.insert(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.CREATE_DATA_ERROR);
        } else {
            ToolBlockRecordPo existing = toolBlockRecordDao.selectById(id);
            Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
            // 更新：直接更新
            entity.setUpdateTime(Moments.now());
            int success = toolBlockRecordDao.update(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.UPDATE_DATA_ERROR, id);
        }

        return entity.getId();
    }

    @Override
    public int delete(Long id) {
        ToolBlockRecordPo existing = toolBlockRecordDao.selectById(id);
        Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
        return toolBlockRecordDao.delete(id);
    }
}