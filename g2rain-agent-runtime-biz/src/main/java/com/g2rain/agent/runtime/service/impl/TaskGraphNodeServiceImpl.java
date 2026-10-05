package com.g2rain.agent.runtime.service.impl;

import com.g2rain.common.exception.SystemErrorCode;
import com.g2rain.common.id.IdGenerator;
import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.utils.Asserts;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.validation.Validations;
import com.g2rain.agent.runtime.converter.TaskGraphNodeConverter;
import com.g2rain.agent.runtime.dao.TaskGraphNodeDao;
import com.g2rain.agent.runtime.dao.po.TaskGraphNodePo;
import com.g2rain.agent.runtime.dto.TaskGraphNodeDto;
import com.g2rain.agent.runtime.dto.TaskGraphNodeSelectDto;
import com.g2rain.agent.runtime.service.TaskGraphNodeService;
import com.g2rain.agent.runtime.vo.TaskGraphNodeVo;
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
 * 任务图步骤表服务实现类
 * 表名: task_graph_node
 *
 * @author G2rain Generator
 */
@Service(value = "taskGraphNodeServiceImpl")
public class TaskGraphNodeServiceImpl implements TaskGraphNodeService {

    @Resource(name = "taskGraphNodeDao")
    private TaskGraphNodeDao taskGraphNodeDao;

    private IdGenerator idGenerator;

    @Qualifier("idGenerator")
    @Autowired(required = false)
    public void setIdGenerator(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public List<TaskGraphNodeVo> selectList(TaskGraphNodeSelectDto selectDto) {
        return taskGraphNodeDao.selectList(selectDto)
            .stream()
            .map(TaskGraphNodeConverter.INSTANCE::po2vo)
            .toList();
    }

    @Override
    public PageData<TaskGraphNodeVo> selectPage(PageSelectListDto<TaskGraphNodeSelectDto> selectDto) {
        Page<TaskGraphNodePo> page = PageContext.of(selectDto.getPageNum(), selectDto.getPageSize(), () -> {
            taskGraphNodeDao.selectList(selectDto.getQuery());
        });
        List<TaskGraphNodeVo> result = page.getResult()
            .stream()
            .map(TaskGraphNodeConverter.INSTANCE::po2vo)
            .toList();
        return PageData.of(page.getPageNum(), page.getPageSize(), page.getTotal(), result);
    }

    @Override
    public Long save(TaskGraphNodeDto dto) {
        if (Objects.isNull(dto.getOrganId())) {
            dto.setOrganId(PrincipalContextHolder.getOrganId());
        }

        Validations.validateSave(dto);

        // 转换 DTO 为 PO
        TaskGraphNodePo entity = TaskGraphNodeConverter.INSTANCE.dto2po(dto);

        // 判断是新增还是更新
        Long id = entity.getId();
        if (Objects.isNull(id) || id == 0) {
            // 新增：使用IdGenerator生成主键
            entity.setId(idGenerator.generateId());
            LocalDateTime now = Moments.now();
            entity.setUpdateTime(now);
            entity.setCreateTime(now);
            int success = taskGraphNodeDao.insert(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.CREATE_DATA_ERROR);
        } else {
            TaskGraphNodePo existing = taskGraphNodeDao.selectById(id);
            Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
            // 更新：直接更新
            entity.setUpdateTime(Moments.now());
            int success = taskGraphNodeDao.update(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.UPDATE_DATA_ERROR, id);
        }

        return entity.getId();
    }

    @Override
    public int delete(Long id) {
        TaskGraphNodePo existing = taskGraphNodeDao.selectById(id);
        Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
        return taskGraphNodeDao.delete(id);
    }
}
