package com.g2rain.agent.runtime.service.impl;

import com.g2rain.common.exception.SystemErrorCode;
import com.g2rain.common.id.IdGenerator;
import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.utils.Asserts;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.validation.Validations;
import com.g2rain.agent.runtime.converter.TaskGraphEdgeConverter;
import com.g2rain.agent.runtime.dao.TaskGraphEdgeDao;
import com.g2rain.agent.runtime.dao.po.TaskGraphEdgePo;
import com.g2rain.agent.runtime.dto.TaskGraphEdgeDto;
import com.g2rain.agent.runtime.dto.TaskGraphEdgeSelectDto;
import com.g2rain.agent.runtime.service.TaskGraphEdgeService;
import com.g2rain.agent.runtime.vo.TaskGraphEdgeVo;
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
 * 任务图依赖边表服务实现类
 * 表名: task_graph_edge
 *
 * @author G2rain Generator
 */
@Service(value = "taskGraphEdgeServiceImpl")
public class TaskGraphEdgeServiceImpl implements TaskGraphEdgeService {

    @Resource(name = "taskGraphEdgeDao")
    private TaskGraphEdgeDao taskGraphEdgeDao;

    private IdGenerator idGenerator;

    @Qualifier("idGenerator")
    @Autowired(required = false)
    public void setIdGenerator(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public List<TaskGraphEdgeVo> selectList(TaskGraphEdgeSelectDto selectDto) {
        return taskGraphEdgeDao.selectList(selectDto)
            .stream()
            .map(TaskGraphEdgeConverter.INSTANCE::po2vo)
            .toList();
    }

    @Override
    public PageData<TaskGraphEdgeVo> selectPage(PageSelectListDto<TaskGraphEdgeSelectDto> selectDto) {
        Page<TaskGraphEdgePo> page = PageContext.of(selectDto.getPageNum(), selectDto.getPageSize(), () -> {
            taskGraphEdgeDao.selectList(selectDto.getQuery());
        });
        List<TaskGraphEdgeVo> result = page.getResult()
            .stream()
            .map(TaskGraphEdgeConverter.INSTANCE::po2vo)
            .toList();
        return PageData.of(page.getPageNum(), page.getPageSize(), page.getTotal(), result);
    }

    @Override
    public Long save(TaskGraphEdgeDto dto) {
        if (Objects.isNull(dto.getOrganId())) {
            dto.setOrganId(PrincipalContextHolder.getOrganId());
        }

        Validations.validateSave(dto);

        // 转换 DTO 为 PO
        TaskGraphEdgePo entity = TaskGraphEdgeConverter.INSTANCE.dto2po(dto);

        // 判断是新增还是更新
        Long id = entity.getId();
        if (Objects.isNull(id) || id == 0) {
            // 新增：使用IdGenerator生成主键
            entity.setId(idGenerator.generateId());
            LocalDateTime now = Moments.now();
            entity.setUpdateTime(now);
            entity.setCreateTime(now);
            int success = taskGraphEdgeDao.insert(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.CREATE_DATA_ERROR);
        } else {
            TaskGraphEdgePo existing = taskGraphEdgeDao.selectById(id);
            Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
            // 更新：直接更新
            entity.setUpdateTime(Moments.now());
            int success = taskGraphEdgeDao.update(entity);
            Asserts.greaterThan(success, 0, SystemErrorCode.UPDATE_DATA_ERROR, id);
        }

        return entity.getId();
    }

    @Override
    public int delete(Long id) {
        TaskGraphEdgePo existing = taskGraphEdgeDao.selectById(id);
        Asserts.isTrue(Objects.nonNull(existing), SystemErrorCode.DATA_NOT_EXISTS, id);
        return taskGraphEdgeDao.delete(id);
    }
}
