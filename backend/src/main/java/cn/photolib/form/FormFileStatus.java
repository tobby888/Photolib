package cn.photolib.form;

public enum FormFileStatus {
    /** 已发出预签名 PUT，还没有被任何提交引用。 */
    PENDING,
    /** 已被一份提交成功的答卷引用，此后不可再被引用、也不会被清理。 */
    ATTACHED
}
