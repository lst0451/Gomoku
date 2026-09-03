package com.gomoku.security;

/**
 * 房间成员资格校验契约
 * 供 STOMP 拦截器在 SUBSCRIBE 阶段校验用户是否有权订阅指定房间主题
 */
public interface RoomMembershipValidator {

    /**
     * 校验指定用户是否已加入指定房间 (作为选手或观战者)
     *
     * @param roomId 房间ID
     * @param userId 用户ID
     * @return true 若用户具有该房间成员资格，否则 false
     */
    boolean isUserInRoom(String roomId, String userId);
}
