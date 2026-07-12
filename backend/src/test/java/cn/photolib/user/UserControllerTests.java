package cn.photolib.user;

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
    @WithMockUser(roles = "ADMIN")
    void admin_shouldUpdateManagerCampus() {
        var current = userService.get(manager.user().id());
        var request = new UserController.UpdateCampusRequest(
                targetCampusId, current.version());

        var result = controller.updateCampus(manager.user().id(), request).data();

        assertThat(result.campusId()).isEqualTo(targetCampusId);
    }

    @Test
    @WithMockUser(roles = "MINISTER")
    void minister_shouldUpdateManagerCampus() {
        var current = userService.get(manager.user().id());
        var request = new UserController.UpdateCampusRequest(
                targetCampusId, current.version());

        var result = controller.updateCampus(manager.user().id(), request).data();

        assertThat(result.campusId()).isEqualTo(targetCampusId);
    }

    @Test
    @WithMockUser(roles = "CAMPUS_MANAGER")
    void campusManager_shouldNotUpdateManagerCampus() {
        var current = userService.get(manager.user().id());
        var request = new UserController.UpdateCampusRequest(
                targetCampusId, current.version());

        assertThatThrownBy(() -> controller.updateCampus(manager.user().id(), request))
                .isInstanceOf(AccessDeniedException.class);
    }
}
