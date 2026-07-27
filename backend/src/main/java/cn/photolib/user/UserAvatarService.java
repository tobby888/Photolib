package cn.photolib.user;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.PublicId;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

@Service
@RequiredArgsConstructor
public class UserAvatarService {
    private static final Logger log = LoggerFactory.getLogger(UserAvatarService.class);
    private static final String OBJECT_PREFIX = "avatars/";

    private final UserMapper userMapper;
    private final ObjectStorageService storage;
    private final UserAvatarValidator validator;

    @Transactional
    public String replace(Long userId, MultipartFile file) throws IOException {
        UserAvatarValidator.ValidatedAvatar avatar = validator.validate(file);
        UserEntity user = require(userId);
        String oldObjectKey = user.getAvatarObjectKey();
        String newObjectKey = OBJECT_PREFIX + userId + "/" + PublicId.next()
                + "." + avatar.extension();

        boolean rollbackCleanupRegistered = false;
        try {
            storage.put(newObjectKey, new ByteArrayInputStream(avatar.bytes()),
                    avatar.bytes().length, avatar.contentType());
            rollbackCleanupRegistered = registerRollbackCleanup(newObjectKey);
            user.setAvatarObjectKey(newObjectKey);
            user.setAvatarContentType(avatar.contentType());
            user.setAvatarSize((long) avatar.bytes().length);
            if (userMapper.updateById(user) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                        "用户资料已被其他操作修改，请重试");
            }
        } catch (RuntimeException exception) {
            // OSS may have accepted the object even if the client observed a timeout.
            // If no transaction callback owns cleanup yet, delete the unique new key now.
            if (!rollbackCleanupRegistered) {
                safeDelete(newObjectKey);
            }
            throw exception;
        }

        cleanupAfterCommit(oldObjectKey);
        return avatarUrl(require(userId));
    }

    @Transactional
    public void delete(Long userId) {
        UserEntity user = require(userId);
        String oldObjectKey = user.getAvatarObjectKey();
        if (!StringUtils.hasText(oldObjectKey)) {
            return;
        }
        user.setAvatarObjectKey(null);
        user.setAvatarContentType(null);
        user.setAvatarSize(null);
        if (userMapper.updateById(user) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "用户资料已被其他操作修改，请重试");
        }
        cleanupAfterCommit(oldObjectKey);
    }

    public AvatarContent open(Long userId) {
        UserEntity user = require(userId);
        if (!StringUtils.hasText(user.getAvatarObjectKey())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户尚未设置头像");
        }
        return new AvatarContent(storage.open(user.getAvatarObjectKey()),
                user.getAvatarContentType(), user.getAvatarSize());
    }

    public void cleanupAfterCommit(String objectKey) {
        if (!isManagedObject(objectKey)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeDelete(objectKey);
                }
            });
        } else {
            safeDelete(objectKey);
        }
    }

    public static String avatarUrl(UserEntity user) {
        if (user == null || user.getId() == null || !StringUtils.hasText(user.getAvatarObjectKey())) {
            return null;
        }
        int revision = user.getVersion() == null ? 1 : user.getVersion();
        return "/api/v1/users/" + user.getId() + "/avatar?v=" + revision;
    }

    private boolean registerRollbackCleanup(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    safeDelete(objectKey);
                }
            }
        });
        return true;
    }

    private UserEntity require(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private boolean isManagedObject(String objectKey) {
        return StringUtils.hasText(objectKey) && objectKey.startsWith(OBJECT_PREFIX);
    }

    private void safeDelete(String objectKey) {
        if (!isManagedObject(objectKey)) {
            return;
        }
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.warn("清理头像对象失败: {}", objectKey, exception);
        }
    }

    public record AvatarContent(InputStream input, String contentType, long size) {
    }
}
