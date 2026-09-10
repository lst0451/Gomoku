package com.gomoku.dto.request;

public record RestartGameRequest(
        String commandId,
        String roomId
) {}
