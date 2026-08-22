package cn.photolib.permission;

public enum PermissionCategory {
    PROJECT("选题"),
    REQUEST("需求"),
    PHOTO("图库"),
    WORKLOG("工时"),
    DIRECTORY("通讯录"),
    MESSAGE("消息"),
    RECRUITMENT("成员招募"),
    STATISTICS("数据统计"),
    MANAGER_CAMPUS("负责人校区编辑");

    private final String label;

    PermissionCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
