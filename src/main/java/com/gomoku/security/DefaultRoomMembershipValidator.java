package com.gomoku.security;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认房间成员资格管理器实现
 * 在 Phase 2 阶段支持内存成员注册与测试，在 Phase 3 可由 GameRoomService 统一托管
 */
@Component
public class DefaultRoomMembershipValidator implements RoomMembershipValidator {

    private final ConcurrentHashMap<String, Set<String>> roomMembers = new ConcurrentHashMap<>();

    @Override
    public boolean isUserInRoom(String roomId, String userId) {
        if (roomId == null || userId == null) {
            return false;
        }
        Set<String> members = roomMembers.get(roomId);
        return members != null && members.contains(userId);
    }

    /**
     * 注册用户为指定房间的成员
     *
     * @param roomId 房间ID
     * @param userId 用户ID
     */
    public void addMember(String roomId, String userId) {
        roomMembers.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    /**
     * 从房间成员中移除指定用户
     *
     * @param roomId 房间ID
     * @param userId 用户ID
     */
    public void removeMember(String roomId, String userId) {
        Set<String> members = roomMembers.get(roomId);
        if (members != null) {
            members.remove(userId);
        }
    }

    /**
     * 清空全部房间成员信息 (常用于测试重置)
     */
    public void clear() {
        roomMembers.clear();
    }
}
