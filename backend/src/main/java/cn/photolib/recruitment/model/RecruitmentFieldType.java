package cn.photolib.recruitment.model;

public enum RecruitmentFieldType {
    SHORT_TEXT,
    LONG_TEXT,
    SINGLE_CHOICE,
    MULTIPLE_CHOICE,
    DATE,
    /** 上传文件：答案是 form_file_upload 的 id 列表，提交时换成文件名、类型和大小。 */
    FILE_UPLOAD
}
