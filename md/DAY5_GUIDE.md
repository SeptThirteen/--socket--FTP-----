# Java FTP 课设 - Day5 详细指导（初学者版）

## 今日目标
实现**文件上传功能（STOR 命令）**，让客户端能够向服务器上传文件：
- 理解文件接收的数据流
- 实现 `STOR <filename>` 命令
- 通过数据连接接收二进制文件内容
- 处理文件覆盖策略（覆盖/重命名/拒绝）
- 处理磁盘空间不足、权限不足等错误

---

## 核心概念补充

### STOR vs RETR：对称设计

| 命令 | 方向 | 服务器操作 | 客户端操作 |
|------|------|----------|----------|
| **RETR** | 服务器 → 客户端 | 读取文件 → 发送 | 接收 → 写入文件 |
| **STOR** | 客户端 → 服务器 | 接收 → 写入文件 | 读取文件 → 发送 |

两者是镜像操作，代码结构非常相似！

### 文件覆盖策略

当上传的文件已存在时，有三种策略：

| 策略 | 行为 | FTP 响应码 | 优点 | 缺点 |
|------|------|----------|------|------|
| **覆盖** | 直接覆盖旧文件 | 150/226 | 简单，符合用户预期 | 可能丢失数据 |
| **拒绝** | 返回错误，不上传 | 553 | 保护旧数据 | 用户需手动处理 |
| **重命名** | 自动重命名（如加时间戳） | 150/226 | 保留旧文件 | 占用更多空间 |

**本课设采用"覆盖"策略**（最简单）。

### STOR 命令工作流程

```
客户端                              服务器
  |                                   |
  |--- PORT 127,0,0,1,p1,p2 --------->|
  |<----------------------------------|
  |    200 PORT OK                    |
  |                                   |
  |--- STOR newfile.txt ------------->|
  |<----------------------------------|
  |    150 Ready to receive           |
  |                                   |
  |<--- 数据连接（服务器主动连接）-----|
  |    [接收文件内容，分块接收]        |
  |---------------------------------->|
  |    (数据连接关闭)                 |
  |                                   |
  |<----------------------------------|
  |    226 Transfer complete          |
```

---

## 项目结构变化

在 Day4 的基础上，修改以下文件：

```
基于socket的FTP设计与实现/
├── md/
│   ├── DAY1_GUIDE.md
│   ├── DAY2_GUIDE.md
│   ├── DAY3_GUIDE.md
│   ├── DAY4_GUIDE.md
│   └── DAY5_GUIDE.md                # 本文档
├── src/
│   ├── FtpServer.java               # 无需修改
│   ├── ClientSession.java           # 需要修改：增加 STOR 命令
│   ├── UserStore.java               # 无需修改
│   ├── PathValidator.java           # 无需修改
│   ├── DataConnection.java          # Day4 已添加 receiveToStream()
│   └── SimpleFtpClient.java         # 需要修改：增加上传测试
├── data/                            # FTP 虚拟根目录
│   ├── test.txt
│   ├── image.jpg
│   ├── public/
│   │   └── readme.txt
│   └── upload/                      # 推荐在此目录测试上传
└── uploads/                         # 客户端上传源目录（测试用）
    ├── client_file.txt              # 需要自己创建
    └── upload_image.jpg             # 需要自己创建
```

---

## 代码实现详解

### 第 1 步：验证 `DataConnection.java` —— 确认 receiveToStream() 方法存在

**回顾**：在 Day4 中我们已经添加了 `receiveToStream()` 方法，用于从数据连接接收数据并写入输出流。

**检查代码**（应该已经存在）：

```java
/**
 * 接收数据并写入输出流（流式接收）
 * 用于文件上传，避免将整个文件加载到内存
 * 
 * @param outputStream 目标输出流（如文件输出流）
 * @return 接收的总字节数
 * @throws IOException 如果接收失败
 */
public long receiveToStream(OutputStream outputStream) throws IOException {
    if (inputStream == null) {
        throw new IOException("Data connection not established");
    }
    
    byte[] buffer = new byte[8192];  // 8KB 缓冲区
    int bytesRead;
    long totalBytes = 0;
    
    // 循环接收并写入
    while ((bytesRead = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
        totalBytes += bytesRead;
    }
    
    outputStream.flush();
    
    System.out.println("[DataConnection] 已接收 " + totalBytes + " 字节");
    return totalBytes;
}
```

**如果没有这个方法**，请回到 Day4 指导文档补充。

---

### 第 2 步：修改 `ClientSession.java` —— 增加 STOR 命令

**需要修改的内容**：
1. 在 `handleCommand()` 的 switch 中增加 `STOR` 的 case
2. 实现 `handleStor(String filename)` 方法
3. 更新 `HELP` 命令

**修改代码**：

#### 2.1 在 switch 中添加 STOR case

在 `handleCommand()` 方法的 switch 语句中，`RETR` case 之后添加：

```java
case "STOR":
    if (!authenticated) {
        reply(530, "请先登录");
    } else {
        handleStor(arg);
    }
    break;
```

#### 2.2 实现 handleStor() 方法

在 `handleRetr()` 方法之后添加：

```java
/**
 * 处理 STOR 命令 - 上传文件
 * 
 * 命令格式：STOR <filename>
 * 
 * 工作流程：
 * 1. 检查是否已设置数据端口（PORT 命令）
 * 2. 验证文件路径安全性
 * 3. 检查目标目录是否存在且可写
 * 4. 发送 150 响应（即将打开数据连接）
 * 5. 建立数据连接
 * 6. 接收数据并分块写入文件
 * 7. 关闭数据连接
 * 8. 发送 226 响应（传输完成）
 * 
 * @param filename 要保存的文件名（相对于当前工作目录）
 */
private void handleStor(String filename) throws IOException {
    // 1. 检查是否已设置数据端口
    if (dataAddress == null) {
        reply(425, "请先使用 PORT 命令");
        return;
    }
    
    // 2. 参数校验
    if (filename == null || filename.trim().isEmpty()) {
        reply(501, "STOR 命令需要参数");
        return;
    }
    
    filename = filename.trim();
    
    // 3. 解析文件路径（相对于当前工作目录）
    Path filePath;
    try {
        filePath = pathValidator.resolvePath(currentWorkingDir, filename);
    } catch (SecurityException e) {
        reply(550, "访问被拒绝: " + e.getMessage());
        return;
    } catch (IOException e) {
        reply(550, "无效的文件路径: " + e.getMessage());
        return;
    }
    
    // 4. 检查父目录是否存在且可写
    Path parentDir = filePath.getParent();
    if (parentDir == null) {
        reply(550, "无法确定父目录");
        return;
    }
    
    if (!Files.exists(parentDir)) {
        reply(550, "目标目录不存在: " + parentDir.getFileName());
        return;
    }
    
    if (!Files.isDirectory(parentDir)) {
        reply(550, "父路径不是目录");
        return;
    }
    
    if (!Files.isWritable(parentDir)) {
        reply(550, "目标目录不可写");
        return;
    }
    
    // 5. 检查文件是否已存在（覆盖策略：直接覆盖）
    boolean fileExists = Files.exists(filePath);
    if (fileExists) {
        System.out.println("[ClientSession] 警告: 文件 " + filename + " 已存在，将被覆盖");
    }
    
    // 6. 发送"即将打开数据连接"的响应
    reply(150, "正在打开二进制模式数据连接以接收 " + filename);
    
    // 7. 建立数据连接并接收文件
    DataConnection dataConn = new DataConnection();
    try {
        // 连接到客户端数据端口
        dataConn.connect(dataAddress);
        
        // 打开文件输出流
        try (OutputStream fileOutput = Files.newOutputStream(filePath, 
                StandardOpenOption.CREATE,           // 不存在则创建
                StandardOpenOption.TRUNCATE_EXISTING  // 存在则清空（覆盖）
            )) {
            
            // 流式接收文件内容
            long bytesReceived = dataConn.receiveToStream(fileOutput);
            
            System.out.println("[ClientSession] 文件 " + filename + " 接收完成: " + 
                             bytesReceived + " 字节");
        }
        
    } catch (IOException e) {
        // 数据连接失败或写入失败
        System.err.println("[ClientSession] 文件上传失败: " + e.getMessage());
        reply(426, "数据连接失败或写入失败: " + e.getMessage());
        
        // 删除不完整的文件（可选）
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                System.out.println("[ClientSession] 已删除不完整的文件: " + filename);
            }
        } catch (IOException deleteEx) {
            System.err.println("[ClientSession] 无法删除不完整的文件: " + deleteEx.getMessage());
        }
        
        return;
    } finally {
        // 无论成功与否，都要关闭数据连接
        dataConn.close();
        // 清空数据地址（要求下次使用前重新设置 PORT）
        dataAddress = null;
    }
    
    // 8. 发送传输完成响应
    reply(226, "传输完成");
}
```

**关键点解释**：

1. **路径安全验证**：
   - 与 RETR 相同，使用 `pathValidator.resolvePath()` 防止越权

2. **目录检查**：
   - `Files.exists(parentDir)`：确保父目录存在
   - `Files.isWritable(parentDir)`：确保有写权限

3. **文件覆盖策略**：
   ```java
   StandardOpenOption.CREATE            // 不存在则创建
   StandardOpenOption.TRUNCATE_EXISTING  // 存在则清空内容（覆盖）
   ```
   - 如果要**拒绝覆盖**，改用 `CREATE_NEW`（文件存在时抛出异常）
   - 如果要**重命名**，在写入前手动检查并生成新文件名

4. **流式接收**：
   - `Files.newOutputStream()`：打开文件输出流
   - `try-with-resources`：自动关闭文件流
   - `dataConn.receiveToStream()`：分块接收，不占用大量内存

5. **错误处理**：
   - 如果上传失败，删除不完整的文件（可选）
   - 返回 426 错误码

6. **FTP 响应码**：
   - 425：未设置数据端口
   - 501：缺少参数
   - 550：文件系统相关错误（目录不存在、不可写、越权）
   - 426：数据连接失败
   - 226：成功完成

#### 2.3 更新 HELP 命令

在 `handleHelp()` 方法中添加 STOR 的说明：

```java
private void handleHelp() throws IOException {
    reply(214, "支持的命令:");
    out.write("  user <用户名>  - 使用用户名登录\r\n");
    out.write("  pass <密码>  - 提供密码\r\n");
    out.write("  port h1,h2,h3,h4,p1,p2 - 设置数据端口\r\n");
    out.write("  list - 列出目录内容\r\n");
    out.write("  retr <文件名> - 下载文件\r\n");
    out.write("  stor <文件名> - 上传文件\r\n");
    out.write("  quit - 断开连接\r\n");
    out.write("  cwd <目录>  - 更改当前目录\r\n");
    out.write("  pwd - 显示当前目录\r\n");
    out.write("  help - 显示此消息\r\n");
    out.flush();
}
```

#### 2.4 导入必需的类

在 `ClientSession.java` 文件顶部添加：

```java
import java.nio.file.StandardOpenOption;
```

---

### 第 3 步：修改 `SimpleFtpClient.java` —— 增加上传测试

**需要添加的内容**：
1. 实现 `stor(String localFilePath, String remoteFilename)` 方法
2. 在 `main()` 中增加上传测试

**新增代码**：

#### 3.1 添加 stor() 方法

在 `retr()` 方法之后添加：

```java
/**
 * 上传文件
 * 
 * @param localFilePath 本地文件路径（绝对路径）
 * @param remoteFilename 服务器上的文件名（相对于当前工作目录）
 */
public void stor(String localFilePath, String remoteFilename) throws IOException {
    ServerSocket tempServerSocket = null;
    Socket dataSocket = null;
    
    try {
        // 0. 检查本地文件是否存在
        File localFile = new File(localFilePath);
        if (!localFile.exists()) {
            System.err.println("[错误] 本地文件不存在: " + localFilePath);
            return;
        }
        
        if (!localFile.isFile()) {
            System.err.println("[错误] 路径不是普通文件: " + localFilePath);
            return;
        }
        
        long fileSize = localFile.length();
        System.out.println("\n[STOR 命令流程]");
        System.out.println("[客户端] 本地文件: " + localFilePath + " (" + fileSize + " 字节)");
        
        // 1. 创建 ServerSocket 监听本地端口
        tempServerSocket = new ServerSocket(0);
        int dataPort = tempServerSocket.getLocalPort();
        System.out.println("[客户端] 本地数据端口: " + dataPort);
        
        // 2. 发送 PORT 命令
        int p1 = dataPort / 256;
        int p2 = dataPort % 256;
        String portCmd = "PORT 127,0,0,1," + p1 + "," + p2;
        System.out.println("[客户端] 发送: " + portCmd);
        
        out.write(portCmd + "\r\n");
        out.flush();
        
        String portResponse = in.readLine();
        System.out.println("[服务器] " + portResponse);
        
        if (!portResponse.startsWith("200")) {
            System.err.println("[错误] PORT 命令失败");
            return;
        }
        
        // 3. 发送 STOR 命令
        System.out.println("[客户端] 发送: STOR " + remoteFilename);
        out.write("STOR " + remoteFilename + "\r\n");
        out.flush();
        
        // 4. 读取 150 响应
        String response150 = in.readLine();
        System.out.println("[服务器] " + response150);
        
        if (!response150.startsWith("150")) {
            System.err.println("[错误] STOR 命令响应异常: " + response150);
            // 尝试读取错误信息
            String errorResponse = in.readLine();
            if (errorResponse != null) {
                System.out.println("[服务器] " + errorResponse);
            }
            return;
        }
        
        // 5. 等待服务器的数据连接
        System.out.println("[客户端] 等待服务器的数据连接...");
        tempServerSocket.setSoTimeout(10000);
        dataSocket = tempServerSocket.accept();
        System.out.println("[客户端] ✓ 数据连接已建立");
        
        // 6. 读取本地文件并发送
        System.out.println("[客户端] 正在发送文件...");
        long startTime = System.currentTimeMillis();
        
        try (FileInputStream fileIn = new FileInputStream(localFile);
             OutputStream dataOut = dataSocket.getOutputStream()) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytes = 0;
            
            while ((bytesRead = fileIn.read(buffer)) != -1) {
                dataOut.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
                
                // 每发送 100KB 打印一次进度（可选）
                if (totalBytes % (100 * 1024) == 0) {
                    System.out.println("[客户端] 已发送: " + (totalBytes / 1024) + " KB");
                }
            }
            
            dataOut.flush();
            
            long endTime = System.currentTimeMillis();
            double seconds = (endTime - startTime) / 1000.0;
            double speed = (totalBytes / 1024.0) / seconds;  // KB/s
            
            System.out.println("[客户端] ✓ 文件发送完成");
            System.out.println("    - 文件大小: " + totalBytes + " 字节 (" + 
                             (totalBytes / 1024) + " KB)");
            System.out.println("    - 耗时: " + String.format("%.2f", seconds) + " 秒");
            System.out.println("    - 速度: " + String.format("%.2f", speed) + " KB/s");
        }
        
        dataSocket.close();
        
        // 7. 读取 226 响应
        String response226 = in.readLine();
        System.out.println("[服务器] " + response226);
        
    } catch (SocketTimeoutException e) {
        System.err.println("[错误] 等待数据连接超时: " + e.getMessage());
    } catch (IOException e) {
        System.err.println("[错误] I/O 异常: " + e.getMessage());
        e.printStackTrace();
    } finally {
        if (dataSocket != null) {
            try {
                dataSocket.close();
            } catch (IOException e) {
                // 忽略
            }
        }
        
        if (tempServerSocket != null && !tempServerSocket.isClosed()) {
            try {
                tempServerSocket.close();
            } catch (IOException e) {
                // 忽略
            }
        }
    }
}
```

#### 3.2 在 main() 中添加上传测试

修改 `main()` 方法，增加上传测试：

```java
public static void main(String[] args) {
    SimpleFtpClient client = new SimpleFtpClient();
    try {
        System.out.println("========== FTP 客户端测试开始 ==========\n");
        
        client.connect("127.0.0.1", 2121);
        client.login("alice", "123456");
        client.pwd();
        
        System.out.println("\n[测试 1] 列出根目录");
        client.list();
        
        System.out.println("\n[测试 2] 下载 test.txt");
        client.retr("test.txt", "downloads/test_downloaded.txt");
        
        System.out.println("\n[测试 3] 上传 client_file.txt 到根目录");
        client.stor("uploads/client_file.txt", "uploaded_file.txt");
        
        System.out.println("\n[测试 4] 验证上传：列出根目录");
        client.list();
        
        System.out.println("\n[测试 5] 切换到 upload 目录并上传文件");
        client.cwd("upload");
        client.stor("uploads/upload_image.jpg", "image_from_client.jpg");
        client.list();
        
        System.out.println("\n[测试 6] 测试覆盖已存在的文件");
        client.stor("uploads/client_file.txt", "uploaded_file.txt");
        
        System.out.println("\n[测试完成] 断开连接");
        client.quit();
        
    } catch (Exception e) {
        System.err.println("[致命错误] " + e.getMessage());
        e.printStackTrace();
    }
}
```

#### 3.3 添加必需的导入

在 `SimpleFtpClient.java` 文件顶部添加：

```java
import java.io.File;
import java.io.FileInputStream;
```

---

## 准备测试环境

### 创建测试文件和目录

在项目根目录下执行：

```powershell
# 进入项目目录
cd "c:\Users\Sept_thirteen\Desktop\计算机网络课设\基于socket 的FTP设计与实现"

# 创建 uploads 目录（客户端上传源目录）
mkdir -Force uploads

# 创建测试文件
"This file is uploaded from client." | Out-File -Encoding UTF8 uploads\client_file.txt

# 创建一个较大的测试文件
$content = "B" * 1024  # 1KB
1..50 | ForEach-Object { $content } | Out-File -Encoding UTF8 uploads\large_upload.txt
# 约 50KB

# 如果有图片文件，复制到 uploads 目录
# Copy-Item "path\to\image.jpg" uploads\upload_image.jpg
```

---

## 编译与运行

### 重新编译

```powershell
cd "c:\Users\Sept_thirteen\Desktop\计算机网络课设\基于socket 的FTP设计与实现"

# 删除旧的 bin 目录
Remove-Item -Recurse -Force bin -ErrorAction SilentlyContinue

# 编译所有 .java 文件
mkdir bin
javac -d bin -encoding UTF-8 src\*.java
```

### 运行测试

**终端 1 - 启动服务器：**
```powershell
cd bin
java data.FtpServer
```

**终端 2 - 运行测试客户端：**
```powershell
cd bin
java data.SimpleFtpClient
```

---

## 预期输出

### 服务器端输出

```
[FtpServer] FTP 服务器启动中...
[FtpServer] FTP 根目录: C:\...\data
[FtpServer] 用户表已初始化
[FtpServer] 线程池已创建，容量=32
[FtpServer] FTP 服务器启动成功，监听端口 2121
[FtpServer] 等待客户端连接...
[FtpServer] 客户端 #1 已连接: 127.0.0.1:xxxxx
...
[ClientSession] 客户端数据端口设置为: /127.0.0.1:xxxxx
[ClientSession] alice <- 200 PORT 命令执行成功
[ClientSession] alice <- 150 正在打开二进制模式数据连接以接收 uploaded_file.txt
[DataConnection] 正在连接到客户端数据端口: /127.0.0.1:xxxxx
[DataConnection] 数据连接已建立
[DataConnection] 已接收 35 字节
[ClientSession] 文件 uploaded_file.txt 接收完成: 35 字节
[DataConnection] 数据连接已关闭
[ClientSession] alice <- 226 传输完成
```

### 客户端输出

```
========== FTP 客户端测试开始 ==========

[客户端] 连接到 127.0.0.1:2121
[服务器] 220 简易 FTP 服务器已准备好

[登录流程]
[客户端] 发送: USER alice
[服务器] 331 用户名正确，需要密码
[客户端] 发送: PASS 123456
[服务器] 230 用户 alice 已登录

[客户端] 发送: PWD
[服务器] 257 "/" 是当前目录

[测试 1] 列出根目录
...

[测试 2] 下载 test.txt
...

[测试 3] 上传 client_file.txt 到根目录

[STOR 命令流程]
[客户端] 本地文件: uploads/client_file.txt (35 字节)
[客户端] 本地数据端口: xxxxx
[客户端] 发送: PORT 127,0,0,1,xxx,xxx
[服务器] 200 PORT 命令执行成功
[客户端] 发送: STOR uploaded_file.txt
[服务器] 150 正在打开二进制模式数据连接以接收 uploaded_file.txt
[客户端] 等待服务器的数据连接...
[客户端] ✓ 数据连接已建立
[客户端] 正在发送文件...
[客户端] ✓ 文件发送完成
    - 文件大小: 35 字节 (0 KB)
    - 耗时: 0.03 秒
    - 速度: 1.17 KB/s
[服务器] 226 传输完成

[测试 4] 验证上传：列出根目录

[LIST 命令流程]
...
===== 目录列表 =====
  public/
  upload/
  test.txt (38 字节)
  uploaded_file.txt (35 字节)  ← 新上传的文件
共 4 项
====================

[测试 5] 切换到 upload 目录并上传文件
...

[测试 6] 测试覆盖已存在的文件
[服务器] 150 正在打开二进制模式数据连接以接收 uploaded_file.txt
[服务器] 226 传输完成
```

---

## 测试场景

### 测试 1：上传小文本文件

**步骤**：
1. 上传 `uploads/client_file.txt` → 服务器的 `uploaded_file.txt`
2. 检查 `data/uploaded_file.txt` 是否存在
3. 对比内容是否一致

**验证**：
```powershell
# 比较原文件和上传后的文件
$original = Get-Content uploads\client_file.txt
$uploaded = Get-Content data\uploaded_file.txt
if ($original -eq $uploaded) {
    Write-Host "✓ 文件内容一致" -ForegroundColor Green
} else {
    Write-Host "✗ 文件内容不一致" -ForegroundColor Red
}
```

### 测试 2：上传较大文件

**步骤**：
1. 上传 `uploads/large_upload.txt`（约 50KB）
2. 观察进度输出
3. 检查文件大小

**验证**：
```powershell
$originalSize = (Get-Item uploads\large_upload.txt).Length
$uploadedSize = (Get-Item data\large_upload.txt).Length

Write-Host "原文件大小: $originalSize 字节"
Write-Host "上传文件大小: $uploadedSize 字节"

if ($originalSize -eq $uploadedSize) {
    Write-Host "✓ 文件大小一致" -ForegroundColor Green
} else {
    Write-Host "✗ 文件大小不一致" -ForegroundColor Red
}
```

### 测试 3：上传二进制文件（图片）

**准备**：
```powershell
# 复制一张图片到 uploads 目录
Copy-Item "C:\path\to\image.jpg" uploads\upload_image.jpg
```

**客户端代码**：
```java
client.stor("uploads/upload_image.jpg", "image_from_client.jpg");
```

**验证**：
- 用图片查看器打开 `data/image_from_client.jpg`
- 应该能正常显示，与原图一致

### 测试 4：文件覆盖

**步骤**：
1. 第一次上传 `client_file.txt` → `test_overwrite.txt`
2. 修改 `client_file.txt` 内容
3. 第二次上传 `client_file.txt` → `test_overwrite.txt`（同名）
4. 检查服务器上的文件是新内容

**验证**：
```powershell
# 第一次上传后
"First version" | Out-File -Encoding UTF8 uploads\client_file.txt
# （通过客户端上传）

# 修改文件
"Second version - OVERWRITTEN" | Out-File -Encoding UTF8 uploads\client_file.txt
# （再次上传）

# 检查服务器上的文件
$content = Get-Content data\test_overwrite.txt
if ($content -like "*OVERWRITTEN*") {
    Write-Host "✓ 文件成功覆盖" -ForegroundColor Green
} else {
    Write-Host "✗ 文件未覆盖" -ForegroundColor Red
}
```

### 测试 5：错误处理

**场景 5.1：上传到不存在的目录**
```java
client.cwd("nonexistent_dir");  // 目录不存在
client.stor("uploads/client_file.txt", "file.txt");
```

**预期输出**：
```
[服务器] 550 目录不存在 或 目录不可写
```

**场景 5.2：上传到不可写的目录**
（需要手动设置文件夹权限为只读）

**预期输出**：
```
[服务器] 550 目标目录不可写
```

**场景 5.3：本地文件不存在**
```java
client.stor("uploads/nonexistent.txt", "file.txt");
```

**预期输出**：
```
[错误] 本地文件不存在: uploads/nonexistent.txt
```

---

## 常见错误与排查

| 错误 | 原因 | 解决方法 |
|------|------|---------|
| `550 目标目录不可写` | 服务器文件夹权限不足 | 检查 data 目录权限，确保可写 |
| `426 数据连接失败` | 网络问题或客户端未监听 | 检查网络，确保防火墙放行 |
| 文件大小为 0 | 客户端未正确发送数据 | 确保 flush() 并检查流关闭顺序 |
| 文件内容损坏 | 编码问题或缓冲区错误 | 使用字节流，不要用字符流 |
| 服务器磁盘满 | 磁盘空间不足 | Java 会抛出 IOException |
| 文件未覆盖 | StandardOpenOption 错误 | 确保使用 TRUNCATE_EXISTING |

---

## Debug 技巧

### 在关键位置加日志

**在 DataConnection.receiveToStream() 中**：

```java
System.out.println("[DEBUG] 开始流式接收");
while ((bytesRead = inputStream.read(buffer)) != -1) {
    System.out.println("[DEBUG] 接收 " + bytesRead + " 字节");
    outputStream.write(buffer, 0, bytesRead);
    totalBytes += bytesRead;
}
System.out.println("[DEBUG] 接收完成，总计 " + totalBytes + " 字节");
```

### 验证文件完整性

```powershell
# 使用 PowerShell 计算文件哈希
$originalHash = Get-FileHash uploads\client_file.txt -Algorithm MD5
$uploadedHash = Get-FileHash data\uploaded_file.txt -Algorithm MD5

if ($originalHash.Hash -eq $uploadedHash.Hash) {
    Write-Host "✓ 文件完全一致（MD5 匹配）" -ForegroundColor Green
} else {
    Write-Host "✗ 文件已损坏（MD5 不匹配）" -ForegroundColor Red
}
```

### 测试不同大小的文件

```powershell
# 创建 1MB 测试文件
$content = "X" * 1024
1..1024 | ForEach-Object { $content } | Out-File -Encoding UTF8 uploads\1mb_test.txt

# 创建 10MB 测试文件（可选）
1..10240 | ForEach-Object { $content } | Out-File -Encoding UTF8 uploads\10mb_test.txt
```

---

## 检查清单

完成 Day5，你应该能做到：

- [ ] `ClientSession.java` 增加了 `handleStor()` 方法
- [ ] switch 语句中添加了 STOR case
- [ ] HELP 命令显示 STOR 说明
- [ ] 导入了 `StandardOpenOption`
- [ ] `SimpleFtpClient.java` 增加了 `stor()` 方法
- [ ] 导入了 `File` 和 `FileInputStream`
- [ ] 服务器启动正常
- [ ] 客户端可以上传小文本文件
- [ ] 客户端可以上传较大文件（50KB+）
- [ ] 上传的文件内容与原文件一致（MD5 匹配）
- [ ] 可以上传二进制文件（图片）
- [ ] 重复上传同名文件会覆盖
- [ ] 上传到不存在的目录收到 550 错误
- [ ] 本地文件不存在时有正确提示
- [ ] 可以在不同目录下上传文件
- [ ] 传输速度显示正常

---

## 核心概念回顾

### 文件上传的完整流程

```
1. PORT 设置数据端口
   ↓
2. STOR 请求上传
   ↓
3. 150 即将打开连接
   ↓
4. 建立数据连接
   ↓
5. 客户端读取文件 → 分块 → 发送
   ↓
6. 服务器接收 → 分块 → 写入文件
   ↓
7. 关闭数据连接
   ↓
8. 226 传输完成
```

### RETR vs STOR 对比

| 方面 | RETR（下载） | STOR（上传） |
|------|------------|------------|
| **数据方向** | 服务器 → 客户端 | 客户端 → 服务器 |
| **服务器操作** | 读取文件 → 发送 | 接收 → 写入文件 |
| **客户端操作** | 接收 → 写入文件 | 读取文件 → 发送 |
| **DataConnection 方法** | sendFromStream() | receiveToStream() |
| **服务器文件检查** | exists, isRegularFile, isReadable | 父目录 isWritable |
| **错误处理** | 550（不存在/不可读） | 550（目录不存在/不可写） |

### StandardOpenOption 详解

| 选项 | 作用 | 组合效果 |
|------|------|---------|
| `CREATE` | 文件不存在时创建 | 覆盖策略（推荐） |
| `TRUNCATE_EXISTING` | 文件存在时清空内容 | 覆盖策略（推荐） |
| `CREATE_NEW` | 文件存在时抛出异常 | 拒绝覆盖策略 |
| `APPEND` | 追加到文件末尾 | 不覆盖，追加内容 |

**本课设使用：`CREATE + TRUNCATE_EXISTING`**

---

## 下一步预告（Day6）

- 实现 `DELE <filename>` 命令：删除文件
- 完善错误处理和响应码（425/426/450/500/530/550）
- 增加超时设置（SO_TIMEOUT）
- 测试多客户端并发连接
- 压力测试（多个文件、大文件）

---

## 参考资源

- [Java OutputStream](https://docs.oracle.com/javase/8/docs/api/java/io/OutputStream.html)
- [Java NIO Files.newOutputStream](https://docs.oracle.com/javase/8/docs/api/java/nio/file/Files.html#newOutputStream-java.nio.file.Path-java.nio.file.OpenOption...-)
- [StandardOpenOption](https://docs.oracle.com/javase/8/docs/api/java/nio/file/StandardOpenOption.html)
- [FTP 协议 - STOR 命令](https://tools.ietf.org/html/rfc959#section-4.1.3)

---

## 常见问题（FAQ）

**Q：为什么要删除不完整的文件？**  
A：如果上传失败，留下不完整的文件可能导致混淆。删除可以保持数据一致性。但也可以保留，供用户手动检查。

**Q：如何实现"拒绝覆盖"策略？**  
A：将 `StandardOpenOption.TRUNCATE_EXISTING` 改为 `StandardOpenOption.CREATE_NEW`，文件存在时会抛出 `FileAlreadyExistsException`。

**Q：如何实现"自动重命名"策略？**  
A：在写入前检查文件是否存在，如果存在，生成新文件名：
```java
if (Files.exists(filePath)) {
    String newName = filename + "_" + System.currentTimeMillis();
    filePath = pathValidator.resolvePath(currentWorkingDir, newName);
}
```

**Q：能否显示上传进度？**  
A：可以。与下载类似，在接收循环中计算百分比：
```java
int progress = (int)((totalBytes * 100) / expectedSize);
System.out.print("\r上传进度: " + progress + "%");
```
但需要客户端先告知文件大小（可通过 SIZE 命令，本课设不要求）。

**Q：如何限制上传文件大小？**  
A：在接收时累计字节数，超过阈值则中止：
```java
long maxSize = 10 * 1024 * 1024;  // 10MB
if (totalBytes > maxSize) {
    throw new IOException("文件过大，超过限制");
}
```

**Q：如何处理磁盘空间不足？**  
A：`Files.newOutputStream().write()` 会抛出 `IOException`，在 catch 块中返回 426 错误即可。

**Q：为什么服务器日志显示"文件已存在，将被覆盖"？**  
A：这是警告信息，提醒管理员文件被覆盖。可以根据需要改为更详细的日志（如记录旧文件的哈希）。

---

**恭喜你完成 Day5！你已经掌握了 FTP 文件上传的核心技术。** 🎉
