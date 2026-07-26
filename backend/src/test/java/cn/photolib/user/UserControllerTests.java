package cn.photolib.user;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class UserControllerTests {
    @Autowired
    private UserController controller;
    @Autowired
    private UserService userService;
    @Autowired
    private CampusService campusService;

    private UserService.CreatedUser manager;
    private Long targetCampusId;

    @BeforeEach
    void setUp() {
        var original = campusService.create("CTRL-OLD", "原校区");
        var target = campusService.create("CTRL-NEW", "新校区");
        manager = userService.create(new UserService.CreateUser(
                "controller-manager", "负责人", UserRole.CAMPUS_MANAGER,
                original.getId(), null, null));
        targetCampusId = target.getId();
    }

    @Test
    @WithMockUser(authorities = "MANAGER_CAMPUS_ASSIGN")
    void admin_shouldUpdateManagerCampus() {
        var current = userService.get(manager.user().id());
        var request = new UserController.UpdateCampusRequest(
                targetCampusId, current.version());

        var result = controller.updateCampus(manager.user().id(), request,
                principal(UserRole.ADMIN)).data();

        assertThat(result.campusId()).isEqualTo(targetCampusId);
    }

    @Test
    @WithMockUser(authorities = "MANAGER_CAMPUS_ASSIGN")
    void minister_shouldUpdateManagerCampus() {
        var current = userService.get(manager.user().id());
        var request = new UserController.UpdateCampusRequest(
                targetCampusId, current.version());

        var result = controller.updateCampus(manager.user().id(), request,
                principal(UserRole.MINISTER)).data();

        assertThat(result.campusId()).isEqualTo(targetCampusId);
    }

    @Test
    @WithMockUser(roles = "CAMPUS_MANAGER")
    void campusManager_shouldNotUpdateManagerCampus() {
        var current = userService.get(manager.user().id());
        var request = new UserController.UpdateCampusRequest(
                targetCampusId, current.version());

        assertThatThrownBy(() -> controller.updateCampus(manager.user().id(), request,
                principal(UserRole.CAMPUS_MANAGER)))
                .isInstanceOf(AccessDeniedException.class);
    }

    private AuthenticatedUser principal(UserRole role) {
        return new AuthenticatedUser(999L, "operator", "操作人", role,
                role == UserRole.CAMPUS_MANAGER ? manager.user().campusId() : null, false);
    }
}
