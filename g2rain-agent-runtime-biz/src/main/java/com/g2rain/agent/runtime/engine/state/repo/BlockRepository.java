package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.dto.ToolBlockRecordDto;
import com.g2rain.agent.runtime.service.ToolBlockRecordService;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工具拦截记录仓储：{@code tool_block_record} 写入。
 *
 * @author alpha
 * @since 2026/11/17
 */
@Component
@RequiredArgsConstructor
public class BlockRepository {

    /**
     * 拒绝说明与下一步的列宽上限。
     */
    private static final int TEXT_MAX = 1000;

    /**
     * 错误码列宽上限。
     */
    private static final int ERROR_CODE_MAX = 64;

    /**
     * 原因编码列宽上限。
     */
    private static final int REASON_CODE_MAX = 128;

    /**
     * 拦截记录表服务。
     */
    private final ToolBlockRecordService toolBlockRecordService;

    /**
     * 插入一行拦截记录；字符串按列宽截断。
     *
     * @param dto 已填必填字段的 DTO
     */
    public void insert(ToolBlockRecordDto dto) {
        dto.setMessage(RuntimeUtils.clip(dto.getMessage(), TEXT_MAX));
        dto.setNextStep(RuntimeUtils.clip(dto.getNextStep(), TEXT_MAX));
        dto.setErrorCode(RuntimeUtils.clip(dto.getErrorCode(), ERROR_CODE_MAX));
        dto.setReasonCode(RuntimeUtils.clip(dto.getReasonCode(), REASON_CODE_MAX));
        toolBlockRecordService.save(dto);
    }
}
