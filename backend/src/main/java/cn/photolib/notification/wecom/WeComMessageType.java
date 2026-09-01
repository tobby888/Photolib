package cn.photolib.notification.wecom;

/**
 * 应用消息用哪种 msgtype 发。
 *
 * <p>默认 {@link #MARKDOWN}：标题、加粗和链接都能正常渲染，通知读起来接近站内信。
 * 但 <b>markdown 消息只有企业微信客户端能看</b>——在微信里通过"企业微信消息"插件收消息
 * 的成员会看到"当前版本不支持该消息类型"。如果你们有人这么用，把
 * {@code WECOM_MESSAGE_TYPE} 设成 {@code text} 全局退回纯文本。
 */
public enum WeComMessageType {
    MARKDOWN("markdown"),
    TEXT("text");

    private final String wireName;

    WeComMessageType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
