package com.g2rain.agent.runtime.service.impl;

import com.g2rain.common.exception.SystemErrorCode;
import com.g2rain.common.id.IdGenerator;
import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.utils.Asserts;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.validation.Validations;
import com.g2rain.agent.runtime.converter.TaskNodePayloadConverter;
import com.g2rain.agent.runtime.dao.TaskNodePayloadDao;
import com.g2rain.agent.runtime.dao.po.TaskNodePayloadPo;
import com.g2rain.agent.runtime.dto.TaskNodePayloadDto;
import com.g2rain.agent.runtime.dto.TaskNodePayloadSelectDto;
import com.g2rain.agent.runtime.service.TaskNodePayloadService;
import com.g2rain.agent.runtime.vo.TaskNodePayloadVo;
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
 * 大报文溢出表服务实现类
 * 表名: task_node_payload
 *
 * @author G2rain Generator
 */
@Service(value = "taskNodePayloadServiceImpl")
public class TaskNodePayloadServiceImpl implements TaskNodePayloadService {

    @Resource(name = "taskNodePayloadDao")
    private TaskNodePayloadDao taskNodePayloadDao;

    private IdGenerator idGenerator;

    @Qualifier("idGenerator")
    @Autowired(required = false)
    public void setIdGenerator(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public List<TaskNodePayloadVo> selectList(TaskNodePayloadSelectDto selectDto) {
        return taskNodePayloadDao.selectList(selectDto)
            .stream()
            .map(TaskNodePayloadConverter.INSTANCE::po2vo)
            .toList();
    }

    @Override
    public PageData<TaskNodePayloadVo> selectPage(PageSelectListDto<TaskNodePayloadSelectDto> selectDto) {
        Page<TaskNodePayloadPo> page = PageContext.of(selectDto.getPageNum(), selectDto.getPageSize(), () -> {
            taskNodePayloadDao.selectList(selectDto.getQuery());
        });
        List<TaskNodePayloadVo> result = page.getResult()
            .stream()
            .map(TaskNodePayloadConverter.INSTANCE::po2vo)
            .toList();
        return PageData.of(page.getPageNum(), page.getPageSize(), page.getTotal(), result);
    }

    @Override
    public Long save(TaskNodePayloadDto dto) {
        if (Objects.isNull(dto.getOrganId())) {
            dto.setOrganId(PrincipalContextHolder.getOrganId());
        }

        Validations.validateSave(dto);

        // 转换 DTO 为 PO
        TaskNodePayloadPo entity = TaskNodePayloadConverter.INSTANCE.dto2po(dto);

        // 判断是新增还是更新
        Long id = entity.getId();
        if (Objects.isNull(id) || id == 0) {
            // 新增：使用IdGenerator生成主键
            entity.setId(idGenerator.generateId());
            LocalDateTime now = Moments.now();
            entity.setUpdateTime(now);
            entity.setCreateTime(now);
            int success = taskNodePayloadDao.insert(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.CREATE_DATA_ERROR);
        } else {
            TaskNodePayloadPo existing = taskNodePayloadDao.selectById(id);
            Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
            // 更新：直接更新
            entity.setUpdateTime(Moments.now());
            int success = taskNodePayloadDao.update(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.UPDATE_DATA_ERROR, id);
        }

        return entity.getId();
    }

    @Override
    public int delete(Long id) {
        TaskNodePayloadPo existing = taskNodePayloadDao.selectById(id);
        Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
        return taskNodePayloadDao.delete(id);
    }
}
