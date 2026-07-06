# PhotoLib OSS Checker

独立运行的 OSS 凭据与 CORS 检查工具，不启动 PhotoLib、数据库或图库对账。

构建：

```powershell
cd backend
.\mvnw.cmd -f ..\tools\oss-checker\pom.xml clean package
```

将 `tools/oss-checker/target/photolib-oss-checker.jar` 与生产 `.env` 放在同一目录后运行：

```bash
java -jar photolib-oss-checker.jar
```

工具只操作 `photolib-credential-check/` 下的随机临时文本对象，并在结束时删除。
可在 `.env` 中通过 `APP_ORIGIN` 指定 CORS 测试来源，默认值为
`https://photowarehouse.cn`。
