package cn.photolib.directory;

import org.junit.jupiter.api.Test;
import cn.photolib.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CampusMemberControllerSecurityTests {
    @Test
    void ministerCanReadDirectoryList() throws Exception {
        PreAuthorize authorization = CampusMemberController.class.getDeclaredMethod(
                "list", Long.class, Boolean.class, AuthenticatedUser.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(authorization.value()).contains("DIRECTORY_VIEW", "DIRECTORY_MANAGE");
    }

    @Test
    void ministerCanMutateDirectory() {
        Arrays.stream(CampusMemberController.class.getDeclaredMethods())
                .filter(method -> Arrays.asList("create", "update", "delete").contains(method.getName()))
                .map(method -> method.getAnnotation(PreAuthorize.class).value())
                .forEach(authorization -> assertThat(authorization).contains("DIRECTORY_MANAGE"));
    }
}
