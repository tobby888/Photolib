package cn.photolib.auth;

import cn.photolib.auth.mapper.AuthSessionMapper;
import cn.photolib.common.error.BusinessException;
import cn.photolib.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthSecurityHardeningTests {
    @Test
    void missingAccountStillPerformsPasswordHashComparison() {
        UserMapper users = mock(UserMapper.class);
        AuthSessionMapper sessions = mock(AuthSessionMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.selectOne(any())).thenReturn(null);
        when(users.selectList(any())).thenReturn(List.of());
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        AuthService service = new AuthService(users, sessions, encoder,
                new AuthProperties(Duration.ofMinutes(15), Duration.ofMinutes(30), true));

        assertThatThrownBy(() -> service.login("missing", "password"))
                .isInstanceOf(BusinessException.class);
        verify(encoder).matches(eq("password"), anyString());
        verifyNoInteractions(sessions);
    }

    @Test
    void productionValidatorRejectsUnsafeDefaults() {
        assertThatThrownBy(() -> DeploymentSecurityValidator.validate(false, true, "strong-password"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure");
        assertThatThrownBy(() -> DeploymentSecurityValidator.validate(true, true, "ChangeMe123!"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("初始密码");
        DeploymentSecurityValidator.validate(true, true, "Strong-Unique-Password-123");
    }
}
