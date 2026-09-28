package cn.photolib.form;

/** 「上传文件」题目的文件挂在谁名下。见 V57 里 form_file_upload 的说明。 */
public enum FormFileOwnerType {
    /** owner_ref 是匿名招募草稿 id。 */
    RECRUITMENT,
    /** owner_ref 是问卷 id，uploader_user_id 是填写人。 */
    SURVEY
}
