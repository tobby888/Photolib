package cn.photolib.user;

import cn.photolib.common.error.BusinessException;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAvatarServiceTests {
    @Test
    void optimisticLockConflictCleansNewObjectOnTransactionRollback() throws Exception {
        UserMapper mapper = mock(UserMapper.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setVersion(1);
        user.setAvatarObjectKey("avatars/42/old.jpg");
        user.setAvatarContentType("image/jpeg");
        user.setAvatarSize(100L);
        when(mapper.selectById(anyLong())).thenReturn(user);
        when(mapper.updateById(any(UserEntity.class))).thenReturn(0);
        UserAvatarService service = new UserAvatarService(
                mapper, storage, new UserAvatarValidator());

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.replace(42L, png()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("其他操作修改");

            ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
            verify(storage).put(objectKey.capture(), any(), anyLong(), any());
            assertThat(objectKey.getValue()).startsWith("avatars/42/");
            verify(storage, never()).delete(any());

            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(storage).delete(objectKey.getValue());
            verify(storage, never()).delete("avatars/42/old.jpg");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void storagePutFailureStillAttemptsToCleanPossiblyPersistedObject() throws Exception {
        UserMapper mapper = mock(UserMapper.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        UserEntity user = new UserEntity();
        user.setId(73L);
        user.setVersion(1);
        when(mapper.selectById(73L)).thenReturn(user);
        doThrow(new IllegalStateException("simulated response timeout")).when(storage)
                .put(any(), any(), anyLong(), any());
        UserAvatarService service = new UserAvatarService(
                mapper, storage, new UserAvatarValidator());

        assertThatThrownBy(() -> service.replace(73L, png()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeout");

        ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
        verify(storage).put(objectKey.capture(), any(), anyLong(), any());
        verify(storage).delete(objectKey.getValue());
        assertThat(objectKey.getValue()).startsWith("avatars/73/");
        verify(mapper, never()).updateById(any(UserEntity.class));
    }

    private MockMultipartFile png() throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new MockMultipartFile("file", "avatar.png", "image/png", output.toByteArray());
    }
}
