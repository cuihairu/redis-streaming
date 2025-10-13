# Redis-Streaming 重构清单

## 重构目标
从 `io.github.cuihairu.redis-streaming` 重命名为 `io.github.cuihairu.redis-streaming`

## 重构进度

### 1. 配置文件更新
- [x] build.gradle - 更新 groupId 为 `io.github.cuihairu.redis-streaming`
- [x] settings.gradle - 无需更新
- [x] gradle.properties - 无需更新
- [x] .github/workflows/*.yml - 更新 CI/CD 配置中的包名引用

### 2. 包名重构 (io.github.cuihairu.redis-streaming -> io.github.cuihairu.redis.streaming)

#### 所有模块
- [x] 所有模块已通过脚本批量重构完成
- [x] Package 声明已更新
- [x] Import 语句已更新
- [x] 完全限定类名已更新

### 3. 资源文件更新
- [x] spring-boot-starter/src/main/resources/META-INF/spring.factories (已通过脚本更新)
- [x] spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports (已通过脚本更新)
- [x] 其他 META-INF 配置文件已检查

### 4. 文档更新
- [x] README.md - 更新项目名称和示例代码
- [x] CLAUDE.md - 更新项目描述和包名
- [x] PUBLISHING.md - 更新 Maven 坐标示例
- [x] QUICK_START.md - 更新示例代码
- [x] RUNNING_EXAMPLES.md - 更新示例代码
- [x] docs/maven-publish.md - 更新 Maven 坐标
- [x] docs/spring-boot-starter-guide.md - 更新依赖示例
- [x] cep/CEP_ISSUES.md - 包名引用无需更新(相对路径)

### 5. 构建验证
- [x] ./gradlew clean
- [x] ./gradlew build (成功)
- [x] ./gradlew test (成功)
- [x] ./gradlew integrationTest (成功 - 需要 Redis/MySQL/PostgreSQL)
- [x] 所有模块编译通过

### 6. Git 相关
- [ ] 更新 .gitignore (如果需要)
- [ ] 建议：GitHub 仓库改名为 redis-streaming

## 重命名映射

```
旧包名: io.github.cuihairu.redis-streaming
新包名: io.github.cuihairu.redis.streaming

旧 GroupId: io.github.cuihairu.redis-streaming
新 GroupId: io.github.cuihairu.redis-streaming
```

## Maven 坐标示例

### 旧的
```xml
<dependency>
    <groupId>io.github.cuihairu.redis-streaming</groupId>
    <artifactId>core</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 新的
```xml
<dependency>
    <groupId>io.github.cuihairu.redis-streaming</groupId>
    <artifactId>core</artifactId>
    <version>0.1.0</version>
</dependency>
```

## 注意事项

1. Java 包名不能包含横线，所以：
   - GroupId: `io.github.cuihairu.redis-streaming` ✅ (可以有横线)
   - 包名: `io.github.cuihairu.redis.streaming` ✅ (用点号代替横线)

2. 包名重构工具：
   - IntelliJ IDEA: Refactor → Rename Package (推荐)
   - 命令行: 使用 find + sed 批量替换

3. 测试策略：
   - 每完成一个模块，立即测试编译
   - 最后运行完整测试套件

## 当前进度
- 已完成: build.gradle groupId 更新
- 进行中: 包名重构
- 待开始: 文档更新

## 预计完成时间
- 包名重构: ~30-60 分钟 (取决于工具)
- 验证测试: ~10-15 分钟
- 文档更新: ~10 分钟
- 总计: ~1-1.5 小时
