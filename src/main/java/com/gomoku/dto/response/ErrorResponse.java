package com.gomoku.dto.response;

import com.gomoku.model.enums.ErrorCode;

/**
 * 私有异常单播响应实体
 */
public record ErrorResponse(
    String commandId,
    ErrorCode errorCode,
    String errorMessage,
    long currentServerVersion,
    long timestamp
) {}
