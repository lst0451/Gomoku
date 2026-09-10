package com.gomoku.dto.request;

/** turnTimeSeconds 为 0 时表示不限时。 */
public record GameSettingsRequest(
        String commandId,
        String roomId,
        Integer turnTimeSeconds
) {}
