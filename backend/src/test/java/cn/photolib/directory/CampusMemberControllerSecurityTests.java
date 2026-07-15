package cn.photolib.directory;

import org.junit.jupiter.api.Test;
import cn.photolib.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

class CampusMemberControllerSecurityTests {
    @Test
    void ministerCannotReadRawDirectoryList() throws Exception {
        PreAuthorize authorization = CampusMemberController.class.getDeclaredMethod(
                "list", Long.class, Boolean.class, AuthenticatedUser.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(authorization.value()).contains("ADMIN", "CAMPUS_MANAGER").doesNotContain("MINISTER");
    }
}
