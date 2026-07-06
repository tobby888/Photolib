package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.request.RequestService;
import cn.photolib.request.mapper.RequestParticipantMapper;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhotoServiceObjectCleanupTests {

    @Test
    void delete_shouldContinueCleaningObjectsAfterOneFailure() {
        PhotoMapper mapper = mock(PhotoMapper.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        PhotoService service = new PhotoService(
                mapper,
                mock(RequestService.class),
                mock(RequestParticipantMapper.class),
                storage,
                mock(StorageProperties.class),
                mock(ApplicationEventPublisher.class),
                mock(JdbcClient.class),
                mock(CampusService.class));
        PhotoEntity photo = new PhotoEntity();
        photo.setId(42L);
        photo.setObjectKey("photos/main.jpg");
        photo.setThumbnailObjectKey("photos/thumb.jpg");
        photo.setOriginalObjectKey("photos/original.jpg");
        when(mapper.selectById(42L)).thenReturn(photo);
        doThrow(new IllegalStateException("temporary failure"))
                .when(storage).delete("photos/main.jpg");

        service.delete(42L, new AuthenticatedUser(
                1L, "admin", "管理员", UserRole.ADMIN, null, false));

        verify(mapper).deleteById(42L);
        verify(storage).delete("photos/main.jpg");
        verify(storage).delete("photos/thumb.jpg");
        verify(storage).delete("photos/original.jpg");
    }
}
