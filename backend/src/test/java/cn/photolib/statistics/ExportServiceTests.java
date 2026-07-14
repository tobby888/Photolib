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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
}
