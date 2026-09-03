package com.gomoku.observability;

import com.gomoku.security.UserPrincipal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * 结构化 MDC 全链路追踪切面 (MdcLoggingAspect)
 * 在 STOMP Controller 消息处理层自动装配与清理 MDC 上下文字段
 */
@Aspect
@Component
public class MdcLoggingAspect {

    @Around("execution(* com.gomoku.controller.GameMessageController.*(..))")
    public Object bindMdcContext(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        String userId = null;
        String roomId = null;
        String commandId = null;

        for (Object arg : args) {
            if (arg instanceof UserPrincipal principal) {
                userId = principal.getUserId();
            } else if (arg instanceof Principal principal) {
                userId = principal.getName();
            } else if (arg instanceof com.gomoku.dto.request.JoinRoomRequest r) {
                roomId = r.roomId();
                commandId = r.commandId();
            } else if (arg instanceof com.gomoku.dto.request.MoveRequest r) {
                roomId = r.roomId();
                commandId = r.commandId();
            } else if (arg instanceof com.gomoku.dto.request.SurrenderRequest r) {
                roomId = r.roomId();
                commandId = r.commandId();
            } else if (arg instanceof com.gomoku.dto.request.ChatMessageRequest r) {
                roomId = r.roomId();
                commandId = r.commandId();
            } else if (arg instanceof com.gomoku.dto.request.AiSwitchRequest r) {
                roomId = r.roomId();
                commandId = r.commandId();
            }
        }

        try {
            if (userId != null) MDC.put("userId", userId);
            if (roomId != null) MDC.put("roomId", roomId);
            if (commandId != null) MDC.put("commandId", commandId);

            return joinPoint.proceed();
        } finally {
            // 安全清理防止线程池上下文污染
            MDC.remove("userId");
            MDC.remove("roomId");
            MDC.remove("commandId");
        }
    }
}
