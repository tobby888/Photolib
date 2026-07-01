package cn.photolib.auth;

import cn.photolib.user.model.UserRole;

public record AuthenticatedUser(
        Long id,
        String username,
        String displayName,
        UserRole role,
        Long campusId,
        boolean mustChangePassword
) {
}
