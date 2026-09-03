package com.gomoku.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;

/**
 * 安全主体对象 (Security Principal)
 * 由服务端鉴权成功后注入，杜绝客户端请求体身份伪造
 */
public class UserPrincipal implements Principal, Serializable {

    private final String userId;
    private final String username;
    private final boolean isGuest;

    public UserPrincipal(String userId, String username, boolean isGuest) {
        this.userId = Objects.requireNonNull(userId, "userId cannot be null");
        this.username = (username != null && !username.isBlank()) ? username : userId;
        this.isGuest = isGuest;
    }

    @Override
    public String getName() {
        return userId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public boolean isGuest() {
        return isGuest;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserPrincipal that)) return false;
        return Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    @Override
    public String toString() {
        return "UserPrincipal{" +
                "userId='" + userId + '\'' +
                ", username='" + username + '\'' +
                ", isGuest=" + isGuest +
                '}';
    }
}
