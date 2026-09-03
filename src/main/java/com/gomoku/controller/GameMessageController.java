package com.gomoku.controller;

import com.gomoku.dto.request.JoinRoomRequest;
import com.gomoku.dto.request.MoveRequest;
import com.gomoku.dto.request.SurrenderRequest;
import com.gomoku.security.UserPrincipal;
import com.gomoku.service.GameRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * STOMP 上行消息映射控制器
 * 严格从 Principal 提取用户身份，所有写操作转交 GameRoomService 串行化执行
 */
@Controller
public class GameMessageController {

    private static final Logger log = LoggerFactory.getLogger(GameMessageController.class);
    private final GameRoomService gameRoomService;

    public GameMessageController(GameRoomService gameRoomService) {
        this.gameRoomService = gameRoomService;
    }

    /**
     * 客户端加入/重连房间：/app/room.join
     */
    @MessageMapping("/room.join")
    public void joinRoom(@Payload JoinRoomRequest request,
                         Principal principal,
                         @Header("simpSessionId") String sessionId) {
        if (principal instanceof UserPrincipal userPrincipal) {
            log.info("Received /app/room.join: userId={}, roomId={}, commandId={}",
                    userPrincipal.getUserId(), request.roomId(), request.commandId());
            gameRoomService.joinRoom(request, userPrincipal, sessionId);
        } else {
            log.warn("Unauthorized /app/room.join call with non-UserPrincipal");
        }
    }

    /**
     * 客户端请求落子：/app/game.move
     */
    @MessageMapping("/game.move")
    public void makeMove(@Payload MoveRequest request,
                         Principal principal) {
        if (principal instanceof UserPrincipal userPrincipal) {
            log.debug("Received /app/game.move: userId={}, roomId={}, row={}, col={}",
                    userPrincipal.getUserId(), request.roomId(), request.row(), request.col());
            gameRoomService.handleMove(request, userPrincipal);
        } else {
            log.warn("Unauthorized /app/game.move call with non-UserPrincipal");
        }
    }

    /**
     * 客户端请求认输：/app/game.surrender
     */
    @MessageMapping("/game.surrender")
    public void surrender(@Payload SurrenderRequest request,
                          Principal principal) {
        if (principal instanceof UserPrincipal userPrincipal) {
            log.info("Received /app/game.surrender: userId={}, roomId={}, commandId={}",
                    userPrincipal.getUserId(), request.roomId(), request.commandId());
            gameRoomService.handleSurrender(request, userPrincipal);
        } else {
            log.warn("Unauthorized /app/game.surrender call with non-UserPrincipal");
        }
    }

    /**
     * 开启人机对战模式：/app/game.ai_switch
     */
    @MessageMapping("/game.ai_switch")
    public void switchAi(@Payload com.gomoku.dto.request.AiSwitchRequest request,
                         Principal principal) {
        if (principal instanceof UserPrincipal userPrincipal) {
            log.info("Received /app/game.ai_switch: userId={}, roomId={}, difficulty={}",
                    userPrincipal.getUserId(), request.roomId(), request.difficulty());
            gameRoomService.switchAi(request, userPrincipal);
        } else {
            log.warn("Unauthorized /app/game.ai_switch call with non-UserPrincipal");
        }
    }
}
