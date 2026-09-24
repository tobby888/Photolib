package cn.photolib.teaching.model;

/**
 * 教学资料的文件格式。
 *
 * <p>首版只真正支持 {@link #PDF}：上传接口只收 PDF，展示也只有一个分支。
 * {@link #WORD} 与 {@link #PPT} 是<b>预留值</b>——把格式做成列而不是硬编码成 PDF，
 * 以后开放 Word/PPT 时不用改表结构，只需放开上传校验与展示分支。</p>
 */
public enum TeachingMaterialFormat {
    PDF,
    WORD,
    PPT
}
