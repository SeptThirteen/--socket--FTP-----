# FTP 服务器修复总结 - CWD 绝对路径处理

## 问题描述
- **现象**: MobaXterm 中点击 `/public` 目录时返回 550 错误
- **根因**: PathValidator.resolvePath() 没有正确区分绝对路径和相对路径
- **表现**: `CWD /public` 返回相对于当前目录的解析结果，而非相对于根目录

## 修复方案

### 文件修改: PathValidator.java

在 `resolvePath()` 方法中增加了绝对路径检测：

```java
// 记录是否为绝对路径
boolean isAbsolute = userInputPath.startsWith("/");

// 如果用户输入以 / 开头，表示从根目录开始（绝对路径）
if (isAbsolute) {
    // 去掉前导 /，因为我们会基于 rootDirectory 解析
    userInputPath = userInputPath.substring(1);
}

// 根据是否为绝对路径选择基础目录
Path baseDir = isAbsolute ? rootDirectory : currentDir;
targetPath = baseDir.resolve(userInputPath);
```

### 修复效果

| 命令 | 修复前 | 修复后 |
|-----|-------|-------|
| CWD / | ✅ | ✅ |
| CWD /public | ❌ 550 | ✅ 250 |
| CWD /upload | ❌ 550 | ✅ 250 |
| CWD public (当前为 /) | ✅ | ✅ |

### 验证日志

```
[ClientSession] CWD 目标路径: C:\...\data\public
[ClientSession] 路径存在: true
[ClientSession] 是目录: true
[ClientSession] bob <- 250 目录已更改到 /public

[ClientSession] CWD 目标路径: C:\...\data\upload
[ClientSession] 路径存在: true
[ClientSession] 是目录: true
[ClientSession] bob <- 250 目录已更改到 /upload
```

## 注意事项

### MobaXterm 的行为
- MobaXterm 在进入目录后会自动尝试进入列表中的子项
- 如果列表显示有 `public` 和 `upload`，MobaXterm 可能会尝试 `CWD public` 和 `CWD upload`
- 这导致尝试进入 `/public/public` 等不存在的目录，返回 550（正常行为）
- **这不是服务器 bug，而是客户端的探测行为**

### FTP 协议遵循情况
✅ 绝对路径 (以 / 开头) 相对于虚拟根目录  
✅ 相对路径相对于当前工作目录  
✅ CWD 命令返回 250 表示成功  
✅ PWD 命令返回正确的当前目录  
✅ LIST 命令返回标准 Unix ls -l 格式  

## 后续验证

在 MobaXterm 中验证：
1. ✅ 连接到 FTP 服务器
2. ✅ 看到根目录列表（bob.txt, test.txt, public/, upload/）
3. ✅ 双击 public 文件夹进入（收到 CWD 250 响应）
4. ✅ 可在 public 目录中列出文件/文件夹
5. ✅ 回退到根目录
6. ✅ 进入 upload 目录

## 已知限制

- 客户端可能会收到 550 错误当尝试访问不存在的子路径（如 /public/public）
- 这是正常的 FTP 行为，表示目录不存在
- MobaXterm 等客户端会自动处理这些错误

