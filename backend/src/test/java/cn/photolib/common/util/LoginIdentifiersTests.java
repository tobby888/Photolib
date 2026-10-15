package cn.photolib.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginIdentifiersTests {
    @Test
    void fullWidthInputFromChineseImeFoldsToAscii() {
        assertThat(LoginIdentifiers.email("ｚｈａｎｇｓａｎ＠ｑｑ．ｃｏｍ")).isEqualTo("zhangsan@qq.com");
        // 中文输入法下把点打成句号，全角、半角两种句号都要认。
        assertThat(LoginIdentifiers.email("zhangsan@qq。com")).isEqualTo("zhangsan@qq.com");
        assertThat(LoginIdentifiers.email("zhangsan@qq｡com")).isEqualTo("zhangsan@qq.com");
    }

    @Test
    void invisibleCharactersFromCopyPasteAreRemoved() {
        // 首尾的全角空格、不换行空格，中间夹带的零宽空格和 BOM。
        assertThat(LoginIdentifiers.email("　Zhang​san@QQ.com ﻿"))
                .isEqualTo("zhangsan@qq.com");
        assertThat(LoginIdentifiers.clean("\t admin‍ \n")).isEqualTo("admin");
    }

    @Test
    void cleanKeepsCaseButEmailLowercases() {
        assertThat(LoginIdentifiers.clean(" Admin ")).isEqualTo("Admin");
        assertThat(LoginIdentifiers.email(" Admin@Example.COM ")).isEqualTo("admin@example.com");
    }

    @Test
    void blankInputHasNoEmail() {
        assertThat(LoginIdentifiers.email(null)).isNull();
        assertThat(LoginIdentifiers.email(" 　​ ")).isNull();
        assertThat(LoginIdentifiers.clean(null)).isEmpty();
    }

    @Test
    void ordinaryIdentifiersPassThroughUnchanged() {
        assertThat(LoginIdentifiers.clean("photo_lib-01.user")).isEqualTo("photo_lib-01.user");
        assertThat(LoginIdentifiers.clean("张三")).isEqualTo("张三");
        assertThat(LoginIdentifiers.email("first.last+tag@sub.example.edu.cn"))
                .isEqualTo("first.last+tag@sub.example.edu.cn");
    }
}
