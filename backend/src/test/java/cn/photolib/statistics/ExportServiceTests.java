package cn.photolib.statistics;

import cn.photolib.admin.AdminAlertMapper;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;
import java.time.LocalDate;

@ExtendWith(MockitoExtension.class)
class ExportServiceTests {
    @Mock
    private ExportJobMapper mapper;
    @Mock
    private StatisticsService statistics;
    @Mock
    private PhotoMapper photoMapper;
    @Mock
    private ObjectStorageService storage;
    @Mock
    private StorageProperties storageProperties;
    @Mock
    private ApplicationEventPublisher events;
    @Mock
    private AdminAlertMapper alertMapper;
    @InjectMocks
    private ExportService exports;

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"ADMIN", "MINISTER"})
    void allowsPrivilegedUsersToCreatePhotoZip(UserRole role) {
        PhotoEntity photo = new PhotoEntity();
        photo.setId(42L);
        photo.setStatus(PhotoStatus.AVAILABLE);
        when(photoMapper.selectList(any())).thenReturn(List.of(photo));
        AuthenticatedUser user = new AuthenticatedUser(
                7L, "privileged", "Privileged User", role, null, false);

        exports.createPhotoZip(List.of(42L), user);

        verify(mapper).insert(any(ExportJobEntity.class));
        ArgumentCaptor<ExportService.PhotoZipRequested> event =
                ArgumentCaptor.forClass(ExportService.PhotoZipRequested.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().photoIds()).containsExactly(42L);
    }

    @Test
    void rejectsCampusManagerWhenAnySelectedPhotoIsNotOwnedAndVisible() {
        PhotoEntity photo = new PhotoEntity();
        photo.setId(42L);
        photo.setUploadedBy(8L);
        photo.setCampusId(9L);
        photo.setStatus(PhotoStatus.AVAILABLE);
        when(photoMapper.selectList(any())).thenReturn(List.of(photo));
        AuthenticatedUser user = new AuthenticatedUser(
                7L, "manager", "Manager", UserRole.CAMPUS_MANAGER, 9L, false);

        assertThatThrownBy(() -> exports.createPhotoZip(List.of(42L), user))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(mapper, events);
    }

    @Test
    void failedJobExposesGenericMessageInsteadOfInternalException() {
        ExportJobEntity job = new ExportJobEntity();
        job.setId("job-id");
        when(mapper.selectById("job-id")).thenReturn(job);
        when(statistics.members(any(), any(), any(), any(), any(), anySet()))
                .thenThrow(new IllegalStateException("C:\\secret\\bucket-name"));

        exports.exportStatistics(new ExportService.StatisticsExportRequested(
                "job-id", LocalDate.now(), LocalDate.now(), null, null));

        ArgumentCaptor<ExportJobEntity> captor = ArgumentCaptor.forClass(ExportJobEntity.class);
        verify(mapper).updateById(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getErrorMessage())
                .isEqualTo("导出任务执行失败，请稍后重试")
                .doesNotContain("secret", "bucket");
    }
}
