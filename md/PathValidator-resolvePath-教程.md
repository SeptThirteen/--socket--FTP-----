# PathValidator.resolvePath 使用教程（面向小白） 🧭

## 一、这是什么？（一句话）
`resolvePath` 是用来把用户在 FTP 中输入的路径（比如 `..`, `/upload/file.txt`，或空字符串）安全地转换成磁盘上的实际路径，并且防止用户跳出服务器根目录（越权）。

---

## 二、为什么要用它？
- 防止路径穿越攻击（用户尝试通过 `../` 访问根目录外的文件）。
- 统一把“相对路径/绝对路径/空输入/."”这些情况规范成一个绝对且安全的 `Path`，方便后续文件操作。

---

## 三、函数签名（简单说明）
```java
public Path resolvePath(String currentWorkingDir, String userInputPath)
        throws SecurityException, IOException
```
- `currentWorkingDir`：当前工作目录，**相对于根目录**，例如 `/`、`/upload`。
- `userInputPath`：用户输入的路径，可能是相对或以 `/` 开头表示根下路径。
- 返回：解析并规范化后的磁盘 `Path`，保证在根目录内；如果越界则抛 `SecurityException`。

---

## 四、内部处理流程（一步步看发生了什么）
1. **构建当前目录**：
   - 如果 `currentWorkingDir` 是 `/` 或空，直接使用 `rootDirectory`；否则把 `currentWorkingDir`（去掉前导 `/`）拼到 `rootDirectory` 上。例：`/upload` → `rootDirectory/upload`。
2. **清理用户输入**：调用 `trim()` 去掉首尾空白；如果以 `/` 开头则去掉前导 `/`（因为我们始终基于 `rootDirectory` 解析）。
3. **拼接路径**：如果 `userInputPath` 为空或为 `.`，目标就是当前目录；否则 `currentDir.resolve(userInputPath)` 来拼接子路径。
4. **规范化**：调用 `normalize()` 去掉 `.` 和 `..`，把路径变干净。
5. **安全检查**：判断结果路径是否以 `rootDirectory` 开头（`startsWith`），否则抛 `SecurityException`。
6. **返回路径**。

---

## 五、例子（最易懂）
假设 `rootDirectory` = `C:\ftp-root`（Windows）或 `/home/ftp`（Unix）。

### 例 1：进入子目录
- `currentWorkingDir` = `/upload`，`userInputPath` = `images/pic.jpg`
- 结果：`C:\ftp-root\upload\images\pic.jpg`

### 例 2：使用 `.` 或空字符串（表示当前目录）
- `userInputPath` = `.` 或 `""` → 返回 `currentWorkingDir` 对应的目录。

### 例 3：防止路径穿越
- `currentWorkingDir` = `/upload`，`userInputPath` = `../..` → `normalize()` 之后路径会尝试跳出 `rootDirectory`，方法会抛 `SecurityException`（拒绝访问）。

### 例 4：以 `/` 开头的路径（从根开始）
- `userInputPath` = `/public/file.txt` → 函数内部会去掉前导 `/`，并把它解析为 `rootDirectory/public/file.txt`，不会被解释为系统根目录的绝对路径。

---

## 六、常见问题与调试技巧 🐞
- Q：`resolve` 会检查文件是否存在吗？
  - A：不会。`resolve` 只是构造路径字符串；如果你想检查存在性，调用 `Files.exists()` 或 `Files.isDirectory()`。
- Q：为什么要 `normalize()` 后再做安全检查？
  - A：因为 `normalize()` 会把 `..` 消掉，否则 `startsWith(rootDirectory)` 可能会被 `../` 绕过。
- 调试技巧：打印 `targetPath` 和 `rootDirectory`（规范化后）来查看是否按预期；当抛 `SecurityException`，输出二者便于定位。

---

## 七、示例测试代码（可复制到你的项目中运行）
```java
public static void main(String[] args) throws Exception {
    PathValidator pv = new PathValidator("C:/ftp-root");

    // 正常路径
    System.out.println(pv.resolvePath("/", "public/index.html"));

    // 当前目录
    System.out.println(pv.resolvePath("/upload", "."));

    // 尝试越权（应抛异常）
    try {
        System.out.println(pv.resolvePath("/upload", "../../etc/passwd"));
    } catch (SecurityException e) {
        System.out.println("被拒绝（越权）: " + e.getMessage());
    }
}
```

---

## 八、给小白的实用建议 ✅
- 在调用 `resolvePath` 前先把 `userInputPath` 做空值检查（避免 NPE）。
- 在需要访问文件之前，额外用 `Files.exists()` 或 `Files.isReadable()` 做一次检查并给用户友好错误信息。
- 对于显示路径给客户端，使用 `toVirtualPath` 把磁盘路径转换回以 `/` 开头的虚拟路径，避免泄露服务器真实目录结构。

---

如果你同意，我可以：
- 把上面的示例加入到 `md/` 里的 README 下一节，或
- 为 `PathValidator` 添加几个单元测试文件并提交到 `src/test`（如果你想我可以加）。

要我接着做哪一项？🔧✨