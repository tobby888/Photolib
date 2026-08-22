package cn.photolib.recruitment.model;

import java.util.List;

public record RecruitmentFormSchema(List<Field> fields) {
    public RecruitmentFormSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public record Field(String id,
                        RecruitmentFieldType type,
                        String label,
                        String helpText,
                        String placeholder,
                        boolean required,
                        List<String> options) {
        public Field {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
