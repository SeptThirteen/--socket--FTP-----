# Java FTP 课设 - Day2 详细指导（初学者版）

## 今日目标
实现**文件系统导航功能**，让用户能够在 FTP 服务器上浏览目录，包括：
- `PWD` 命令：显示当前工作目录
- `CWD <path>` 命令：切换目录
- 维护每个会话的当前工作目录状态
- **路径安全校验**：防止用户通过 `..` 越权访问根目录之外的内容

---

## 核心知识补充

### 文件系统与路径

| 概念 | 说明 | 示例 |
|------|------|------|
| **绝对路径** | 从根目录开始的完整路径 | `C:\data` (Windows), `/data` (Linux) |
| **相对路径** | 相对于当前目录的路径 | `./files` 或 `files` |
| **父目录** | 使用 `..` 表示上一级目录 | `cd ..` 返回上一级 |
| **根目录** | FTP 服务器的虚拟根目录 | 例如 `data/` 文件夹 |

### Java NIO.2 Path API

| 类/方法 | 作用 |
|---------|------|
| **Path** | 代表文件系统的路径（JDK 7+） |
| **Paths.get(String)** | 从字符串创建 Path 对象 |
| **path.resolve(other)** | 将相对路径连接到当前路径 |
| **path.normalize()** | 规范化路径（去除 `.` 和 `..`） |
| **path.startsWith(prefix)** | 检查路径是否以某前缀开头 |
| **path.toAbsolutePath()** | 获取绝对路径 |
| **Files.isDirectory(path)** | 检查路径是否为目录 |

### 安全问题：路径遍历攻击

**攻击场景**：用户输入 `CWD ../../etc`，试图跳出 FTP 根目录访问系统敏感目录。

**防御策略**：
1. 设置虚拟根目录（例如 `data/`）
2. 所有路径操作都基于根目录
3. 规范化后检查路径是否仍在根目录内
4. 如果路径越界，拒绝操作

---

## 项目结构变化

在 Day1 的基础上，修改以下文件：

```
基于socket的FTP设计与实现/
├── DAY1_GUIDE.md
├── md/
│   └── DAY2_GUIDE.md                # 本文档
├── src/
│   ├── FtpServer.java               # 需要修改：传入根目录
│   ├── ClientSession.java           # 需要修改：增加目录相关功能
│   ├── UserStore.java               # 无需修改
│   └── PathValidator.java           # 新增：路径安全校验工具类
├── data/                            # FTP 虚拟根目录
│   ├── public/                      # 子目录示例
│   │   └── readme.txt
│   ├── upload/                      # 子目录示例
│   └── test.txt
└── README.md
```

**需要做的修改**：
1. **新增 `PathValidator.java`**：封装路径安全校验逻辑
2. **修改 `ClientSession.java`**：增加 `PWD`、`CWD` 命令处理，维护工作目录
3. **修改 `FtpServer.java`**：传入根目录路径给 `ClientSession`

---

## 代码实现详解

### 第 1 步：新增 `PathValidator.java` —— 路径安全校验工具

**目的**：集中管理路径操作和安全校验，防止路径遍历攻击。

**文件位置**：`src/PathValidator.java`

**代码**：

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径验证和操作工具类
 * 
 * 作用：
 * 1. 管理 FTP 虚拟根目录
 * 2. 验证用户请求的路径是否安全（防止越权）
 * 3. 将用户提供的相对路径转换为实际的文件系统路径
 */
public class PathValidator {
    
    /** FTP 虚拟根目录的绝对路径 */
    private final Path rootDirectory;
    
    /**
     * 构造方法
     * 
     * @param rootPath FTP 虚拟根目录的字符串路径
     * @throws IOException 如果根目录不存在或不是目录
     */
    public PathValidator(String rootPath) throws IOException {
        // 将字符串路径转换为 Path 对象
        Path root = Paths.get(rootPath);
        
        // 转换为绝对路径并规范化（去除多余的 . 和 ..）
        this.rootDirectory = root.toAbsolutePath().normalize();
        
        // 检查根目录是否存在
        if (!Files.exists(this.rootDirectory)) {
            throw new IOException("Root directory does not exist: " + this.rootDirectory);
        }
        
        // 检查是否为目录
        if (!Files.isDirectory(this.rootDirectory)) {
            throw new IOException("Root path is not a directory: " + this.rootDirectory);
        }
        
        System.out.println("[PathValidator] FTP 根目录设置为: " + this.rootDirectory);
    }
    
    /**
     * 获取 FTP 虚拟根目录
     */
    public Path getRootDirectory() {
        return rootDirectory;
    }
    
    /**
     * 解析用户路径并验证安全性
     * 
     * @param currentWorkingDir 当前工作目录（相对于根目录的路径，如 "/upload"）
     * @param userInputPath 用户输入的路径（可能是相对路径或绝对路径）
     * @return 解析后的实际文件系统路径（如果安全且有效）
     * @throws SecurityException 如果路径尝试越出根目录
     * @throws IOException 如果路径不存在或不是目录
     */
    public Path resolvePath(String currentWorkingDir, String userInputPath) 
            throws SecurityException, IOException {
        
        // 1. 处理用户输入路径
        userInputPath = userInputPath.trim();
        
        // 如果用户输入以 / 开头，表示从根目录开始（绝对路径）
        if (userInputPath.startsWith("/")) {
            // 去掉前导 /，因为我们会基于 rootDirectory 解析
            userInputPath = userInputPath.substring(1);
        }
        
        // 2. 构建当前工作目录的实际路径
        // currentWorkingDir 是相对于根目录的，例如 "/upload" 或 "/"
        Path currentDir;
        if (currentWorkingDir.equals("/") || currentWorkingDir.isEmpty()) {
            currentDir = rootDirectory;
        } else {
            // 去掉前导 /，因为 resolve 不需要它
            String cleanCwd = currentWorkingDir.startsWith("/") 
                ? currentWorkingDir.substring(1) 
                : currentWorkingDir;
            currentDir = rootDirectory.resolve(cleanCwd);
        }
        
        // 3. 解析用户路径（基于当前工作目录）
        Path targetPath;
        if (userInputPath.isEmpty() || userInputPath.equals(".")) {
            // 用户输入为空或 "."，表示当前目录
            targetPath = currentDir;
        } else {
            // 将用户路径附加到当前目录
            targetPath = currentDir.resolve(userInputPath);
        }
        
        // 4. 规范化路径（处理 . 和 ..）
        targetPath = targetPath.normalize();
        
        // 5. 安全检查：确保目标路径仍在根目录内
        if (!targetPath.startsWith(rootDirectory)) {
            throw new SecurityException("Access denied: path outside root directory");
        }
        
        // 6. 返回安全的路径
        return targetPath;
    }
    
    /**
     * 将实际文件系统路径转换为虚拟 FTP 路径（相对于根目录）
     * 
     * @param absolutePath 实际的文件系统绝对路径
     * @return 虚拟 FTP 路径（总是以 / 开头）
     */
    public String toVirtualPath(Path absolutePath) {
        // 规范化路径
        absolutePath = absolutePath.normalize();
        
        // 计算相对于根目录的相对路径
        Path relativePath = rootDirectory.relativize(absolutePath);
        
        // 转换为字符串，统一使用 / 作为分隔符
        String virtualPath = "/" + relativePath.toString().replace("\\", "/");
        
        // 如果相对路径为空，说明就是根目录
        if (virtualPath.equals("/.")) {
            return "/";
        }
        
        return virtualPath;
    }
    
    /**
     * 检查路径是否存在且为目录
     * 
     * @param path 要检查的路径
     * @return true 如果存在且为目录，否则 false
     */
    public boolean isValidDirectory(Path path) {
        return Files.exists(path) && Files.isDirectory(path);
    }
}
```

**关键点解释**：

1. **Path vs String**：
   - `String`：只是文本，不验证是否存在
   - `Path`：代表文件系统路径，可以进行规范化、连接等操作

2. **resolve()**：路径拼接
   - `Paths.get("/data").resolve("upload")` → `/data/upload`
   - `Paths.get("/data").resolve("../etc")` → `/data/../etc`（需要 normalize）

3. **normalize()**：去除 `.` 和 `..`
   - `/data/upload/../public` → `/data/public`
   - `/data/./upload` → `/data/upload`

4. **startsWith()**：检查前缀
   - `/data/upload`.startsWith(`/data`) → `true`
   - `/etc/passwd`.startsWith(`/data`) → `false` ❌ 越权！

5. **relativize()**：计算相对路径
   - `rootDirectory.relativize(absolutePath)` 返回从根目录到目标的相对路径

---

### 第 2 步：修改 `ClientSession.java` —— 增加目录功能

**需要修改的内容**：
1. 增加成员变量：`rootPath`、`currentWorkingDir`、`pathValidator`
2. 修改构造方法：接收根目录并初始化 `PathValidator`
3. 增加命令处理：`PWD`、`CWD`
4. 更新 `HELP` 命令

**完整修改后的代码**：

```java
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 客户端会话处理器（Day2 版本）
 * 
 * 新增功能：
 * - 维护当前工作目录
 * - 支持 PWD（显示当前目录）
 * - 支持 CWD（切换目录）
 * - 路径安全校验
 */
public class ClientSession implements Runnable {
    
    // ==================== 成员变量 ====================
    
    /** 与客户端通信的 Socket */
    private final Socket controlSocket;
    
    /** 从 Socket 读取数据的 Reader */
    private final BufferedReader in;
    
    /** 向 Socket 写入数据的 Writer */
    private final BufferedWriter out;
    
    /** 用户表管理器 */
    private final UserStore userStore;
    
    /** 路径验证器 */
    private final PathValidator pathValidator;
    
    // ==================== 会话状态 ====================
    
    /** 是否已认证 */
    private boolean authenticated = false;
    
    /** 当前登录的用户名 */
    private String currentUser = null;
    
    /** 当前工作目录（虚拟路径，相对于根目录，总是以 / 开头）*/
    private String currentWorkingDir = "/";
    
    // ==================== 构造方法 ====================
    
    /**
     * 初始化一个会话（Day2 版本）
     * 
     * @param controlSocket 与客户端连接的 Socket
     * @param rootPath FTP 虚拟根目录路径
     * @param userStore 用户表管理器
     * @throws IOException 如果 Socket 读写出错或根目录无效
     */
    public ClientSession(Socket controlSocket, String rootPath, UserStore userStore) 
            throws IOException {
        this.controlSocket = controlSocket;
        this.userStore = userStore;
        
        // 初始化路径验证器
        this.pathValidator = new PathValidator(rootPath);
        
        // 初始化当前工作目录为根目录
        this.currentWorkingDir = "/";
        
        // 初始化输入输出流
        InputStream socketInput = controlSocket.getInputStream();
        this.in = new BufferedReader(
            new InputStreamReader(socketInput, StandardCharsets.UTF_8)
        );
        
        OutputStream socketOutput = controlSocket.getOutputStream();
        this.out = new BufferedWriter(
            new OutputStreamWriter(socketOutput, StandardCharsets.UTF_8)
        );
    }
    
    // ==================== 核心方法 ====================
    
    @Override
    public void run() {
        try {
            controlSocket.setSoTimeout(300000);
            reply(220, "Simple FTP Server Ready");
            
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                handleCommand(line);
            }
        } catch (IOException e) {
            System.out.println("[ClientSession] 客户端 " + currentUser + " 断开连接: " + e.getMessage());
        } finally {
            try {
                controlSocket.close();
            } catch (IOException e) {
                // 忽略关闭错误
            }
        }
    }
    
    /**
     * 处理一条 FTP 命令
     */
    private void handleCommand(String commandLine) {
        try {
            String[] parts = commandLine.split("\\s+", 2);
            String cmd = parts[0].toUpperCase(Locale.ROOT);
            String arg = (parts.length > 1) ? parts[1] : "";
            
            switch (cmd) {
                case "USER":
                    handleUser(arg);
                    break;
                case "PASS":
                    handlePass(arg);
                    break;
                case "QUIT":
                    handleQuit();
                    break;
                case "PWD":
                    requireAuth(() -> handlePwd());
                    break;
                case "CWD":
                    requireAuth(() -> handleCwd(arg));
                    break;
                case "HELP":
                    handleHelp();
                    break;
                default:
                    reply(500, "Unknown command: " + cmd);
            }
        } catch (Exception e) {
            try {
                reply(500, "Server error: " + e.getMessage());
            } catch (IOException ignored) {
            }
        }
    }
    
    // ==================== 权限检查工具 ====================
    
    /**
     * 要求用户已认证，否则返回 530
     * 
     * @param action 需要认证后执行的操作
     */
    private void requireAuth(IOAction action) throws IOException {
        if (!authenticated) {
            reply(530, "Not logged in");
            return;
        }
        action.execute();
    }
    
    /**
     * 函数式接口：可能抛出 IOException 的操作
     */
    @FunctionalInterface
    private interface IOAction {
        void execute() throws IOException;
    }
    
    // ==================== 命令处理函数 ====================
    
    /**
     * 处理 USER 命令（无变化）
     */
    private void handleUser(String username) throws IOException {
        if (username == null || username.trim().isEmpty()) {
            reply(501, "USER requires an argument");
            return;
        }
        
        username = username.trim();
        
        if (!userStore.userExists(username)) {
            reply(530, "Not a valid user");
            return;
        }
        
        currentUser = username;
        authenticated = false;
        reply(331, "User name ok, need password");
    }
    
    /**
     * 处理 PASS 命令（无变化）
     */
    private void handlePass(String password) throws IOException {
        if (password == null || password.trim().isEmpty()) {
            reply(501, "PASS requires an argument");
            return;
        }
        
        password = password.trim();
        
        if (currentUser == null) {
            reply(503, "Login with USER first");
            return;
        }
        
        if (userStore.authenticate(currentUser, password)) {
            authenticated = true;
            reply(230, "User " + currentUser + " logged in");
        } else {
            currentUser = null;
            authenticated = false;
            reply(530, "Login incorrect");
        }
    }
    
    /**
     * 处理 QUIT 命令（无变化）
     */
    private void handleQuit() throws IOException {
        reply(221, "Goodbye");
        controlSocket.close();
    }
    
    /**
     * 处理 PWD 命令 - 显示当前工作目录
     * 
     * FTP 响应格式：257 "<directory>" is current directory
     */
    private void handlePwd() throws IOException {
        // 返回虚拟路径（相对于 FTP 根目录）
        reply(257, "\"" + currentWorkingDir + "\" is current directory");
    }
    
    /**
     * 处理 CWD 命令 - 切换目录
     * 
     * @param targetPath 用户输入的目标路径
     * 
     * FTP 响应：
     * - 250：切换成功
     * - 550：路径不存在或越权
     */
    private void handleCwd(String targetPath) throws IOException {
        // 1. 参数校验
        if (targetPath == null || targetPath.trim().isEmpty()) {
            reply(501, "CWD requires an argument");
            return;
        }
        
        targetPath = targetPath.trim();
        
        try {
            // 2. 解析并验证路径安全性
            Path resolvedPath = pathValidator.resolvePath(currentWorkingDir, targetPath);
            
            // 3. 检查路径是否存在且为目录
            if (!pathValidator.isValidDirectory(resolvedPath)) {
                reply(550, "Directory does not exist: " + targetPath);
                return;
            }
            
            // 4. 更新当前工作目录（转换为虚拟路径）
            currentWorkingDir = pathValidator.toVirtualPath(resolvedPath);
            
            // 5. 返回成功响应
            reply(250, "Directory changed to " + currentWorkingDir);
            
        } catch (SecurityException e) {
            // 路径越权
            reply(550, "Access denied: " + e.getMessage());
        } catch (IOException e) {
            // 其他 IO 错误
            reply(550, "Cannot change directory: " + e.getMessage());
        }
    }
    
    /**
     * 处理 HELP 命令 - 显示支持的命令（更新）
     */
    private void handleHelp() throws IOException {
        reply(214, "Commands supported:");
        out.write("  USER <username>  - Login with username\r\n");
        out.write("  PASS <password>  - Provide password\r\n");
        out.write("  PWD              - Print working directory\r\n");
        out.write("  CWD <path>       - Change working directory\r\n");
        out.write("  QUIT             - Disconnect\r\n");
        out.write("  HELP             - Show this message\r\n");
        out.flush();
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 向客户端发送 FTP 响应
     */
    private void reply(int code, String message) throws IOException {
        String response = code + " " + message + "\r\n";
        out.write(response);
        out.flush();
        System.out.println("[ClientSession] " + currentUser + " <- " + response.trim());
    }
}
```

**关键修改点解释**：

1. **新增成员变量**：
   - `pathValidator`：路径验证器
   - `currentWorkingDir`：当前工作目录（虚拟路径，如 `/upload`）

2. **构造方法变化**：
   - 接收 `rootPath` 参数
   - 初始化 `PathValidator`

3. **requireAuth() 方法**：
   - 使用函数式接口 `IOAction`，简化认证检查逻辑
   - 避免重复 `if (!authenticated)` 判断

4. **handlePwd()**：
   - 直接返回 `currentWorkingDir`
   - 响应码 257

5. **handleCwd()**：
   - 调用 `pathValidator.resolvePath()` 解析路径
   - 检查目录是否存在
   - 更新 `currentWorkingDir`
   - 捕获 `SecurityException`（越权）和 `IOException`（其他错误）

---

### 第 3 步：修改 `FtpServer.java` —— 传入根目录

**需要修改的内容**：
- 在 `main()` 方法中指定根目录路径
- 将根目录传递给 `ClientSession`

**修改后的代码**：

```java
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FTP 服务器主类（Day2 版本）
 * 
 * 新增：指定并传递 FTP 根目录
 */
public class FtpServer {
    
    private static final int CONTROL_PORT = 2121;
    private static final int POOL_SIZE = 32;
    
    /** FTP 虚拟根目录 */
    private static final String FTP_ROOT_DIR = "data";
    
    public static void main(String[] args) {
        // 1. 验证根目录是否存在
        Path rootPath = Paths.get(FTP_ROOT_DIR);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            System.err.println("[FtpServer] 错误：根目录不存在或不是目录: " + rootPath.toAbsolutePath());
            System.err.println("[FtpServer] 请创建 data 目录并重新运行");
            return;
        }
        
        System.out.println("[FtpServer] FTP 根目录: " + rootPath.toAbsolutePath());
        
        // 2. 创建用户表
        UserStore userStore = UserStore.create();
        System.out.println("[FtpServer] 用户表已初始化");
        
        // 3. 创建线程池
        ExecutorService threadPool = Executors.newFixedThreadPool(POOL_SIZE);
        System.out.println("[FtpServer] 线程池已创建，容量=" + POOL_SIZE);
        
        try {
            // 4. 启动服务器
            ServerSocket serverSocket = new ServerSocket(CONTROL_PORT);
            System.out.println("[FtpServer] FTP 服务器启动成功，监听端口 " + CONTROL_PORT);
            System.out.println("[FtpServer] 等待客户端连接...");
            
            // 5. 主循环：接受连接
            int clientCount = 0;
            while (true) {
                Socket clientSocket = serverSocket.accept();
                clientCount++;
                
                String clientAddr = clientSocket.getInetAddress().getHostAddress() + ":" + 
                                    clientSocket.getPort();
                System.out.println("[FtpServer] 客户端 #" + clientCount + " 已连接: " + clientAddr);
                
                try {
                    // 创建会话，传入根目录路径
                    ClientSession session = new ClientSession(
                        clientSocket, 
                        FTP_ROOT_DIR,  // 传入根目录
                        userStore
                    );
                    
                    threadPool.submit(session);
                    
                } catch (IOException e) {
                    System.out.println("[FtpServer] 创建会话失败: " + e.getMessage());
                    try {
                        clientSocket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            
        } catch (IOException e) {
            System.err.println("[FtpServer] 服务器启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

**关键修改点解释**：

1. **新增常量 `FTP_ROOT_DIR`**：指定根目录为 `data`
2. **启动时验证根目录**：检查 `data` 目录是否存在
3. **传递根目录**：`new ClientSession(clientSocket, FTP_ROOT_DIR, userStore)`

---

## 准备测试环境

### 创建测试目录结构

在项目根目录下，创建如下目录和文件：

```
data/
├── test.txt          （内容：This is a test file）
├── public/
│   └── readme.txt    （内容：Public directory）
└── upload/
    └── sample.txt    （内容：Upload directory）
```

**PowerShell 命令**：

```powershell
# 进入项目目录
cd "c:\Users\Sept_thirteen\Desktop\计算机网络课设\基于socket 的FTP设计与实现"

# 创建目录结构
mkdir -Force data\public
mkdir -Force data\upload

# 创建文件
"This is a test file" | Out-File -Encoding UTF8 data\test.txt
"Public directory" | Out-File -Encoding UTF8 data\public\readme.txt
"Upload directory" | Out-File -Encoding UTF8 data\upload\sample.txt
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

如果编译成功，无输出；如果有错误，会显示错误信息。

### 运行服务器

```powershell
cd bin
java FtpServer
```

**预期输出**：
```
[PathValidator] FTP 根目录设置为: C:\Users\...\data
[FtpServer] FTP 根目录: C:\Users\...\data
[FtpServer] 用户表已初始化
[FtpServer] 线程池已创建，容量=32
[FtpServer] FTP 服务器启动成功，监听端口 2121
[FtpServer] 等待客户端连接...
```

---

## 测试方法

### 方法 1：Telnet 测试（完整测试流程）

打开另一个 PowerShell 终端：

```powershell
telnet localhost 2121
```

**测试场景 1：基本导航**

```
# 服务器响应
220 Simple FTP Server Ready

# 登录
USER alice
331 User name ok, need password

PASS 123456
230 User alice logged in

# 查看当前目录
PWD
257 "/" is current directory

# 切换到 public 目录
CWD public
250 Directory changed to /public

# 再次查看当前目录
PWD
257 "/public" is current directory

# 切换到上级目录（返回根目录）
CWD ..
250 Directory changed to /

# 切换到 upload 目录
CWD upload
250 Directory changed to /upload

PWD
257 "/upload" is current directory
```

**测试场景 2：绝对路径**

```
# 当前在 /upload，直接切换到 /public
CWD /public
250 Directory changed to /public

PWD
257 "/public" is current directory
```

**测试场景 3：路径安全测试（越权尝试）**

```
# 尝试越权到上级目录（应该失败）
CWD ..
250 Directory changed to /

# 再尝试越权
CWD ..
550 Access denied: path outside root directory

# 尝试直接访问系统目录
CWD /../../Windows
550 Access denied: path outside root directory

# 尝试访问不存在的目录
CWD nonexistent
550 Directory does not exist: nonexistent
```

**测试场景 4：相对路径导航**

```
# 从根目录切换到 public/../../upload（应该规范化为 /upload）
CWD /
250 Directory changed to /

CWD public/../upload
250 Directory changed to /upload

PWD
257 "/upload" is current directory
```

### 方法 2：PowerShell 自动化测试脚本

创建文件 `test_day2.ps1`：

```powershell
# 连接服务器
$socket = New-Object System.Net.Sockets.TcpClient("127.0.0.1", 2121)
$stream = $socket.GetStream()
$reader = New-Object System.IO.StreamReader($stream)
$writer = New-Object System.IO.StreamWriter($stream)
$writer.AutoFlush = $true

function Send-Command($cmd) {
    Write-Host "客户端: $cmd" -ForegroundColor Cyan
    $writer.WriteLine($cmd)
    $response = $reader.ReadLine()
    Write-Host "服务器: $response" -ForegroundColor Green
    return $response
}

# 读取欢迎
$welcome = $reader.ReadLine()
Write-Host "服务器: $welcome" -ForegroundColor Green

# 登录
Send-Command "USER alice"
Send-Command "PASS 123456"

# 测试 PWD
Send-Command "PWD"

# 测试 CWD
Send-Command "CWD public"
Send-Command "PWD"

Send-Command "CWD .."
Send-Command "PWD"

Send-Command "CWD upload"
Send-Command "PWD"

# 测试越权
Send-Command "CWD .."
Send-Command "CWD .."  # 应该失败

# 测试不存在的目录
Send-Command "CWD nonexistent"

# 退出
Send-Command "QUIT"

$socket.Close()
```

运行：
```powershell
powershell -ExecutionPolicy Bypass -File test_day2.ps1
```

---

## 常见错误与排查

| 错误 | 原因 | 解决方法 |
|------|------|---------|
| `Root directory does not exist` | `data` 目录不存在 | 在项目根目录创建 `data` 文件夹 |
| `cannot find symbol: PathValidator` | 未编译 `PathValidator.java` | 确保执行 `javac -d bin src\*.java` |
| `550 Directory does not exist` | 目标目录不存在 | 检查 `data/` 下是否有对应子目录 |
| `550 Access denied` | 路径越权 | 正常行为，表示安全校验生效 |
| CWD 后 PWD 仍显示旧目录 | `currentWorkingDir` 未更新 | 检查 `handleCwd()` 是否调用了 `pathValidator.toVirtualPath()` |

---

## Debug 技巧

### 在关键位置加日志

在 `PathValidator.resolvePath()` 中：

```java
System.out.println("[DEBUG] currentWorkingDir=" + currentWorkingDir);
System.out.println("[DEBUG] userInputPath=" + userInputPath);
System.out.println("[DEBUG] targetPath=" + targetPath);
System.out.println("[DEBUG] normalized=" + targetPath.normalize());
```

在 `ClientSession.handleCwd()` 中：

```java
System.out.println("[DEBUG] CWD 前: " + currentWorkingDir);
System.out.println("[DEBUG] CWD 后: " + pathValidator.toVirtualPath(resolvedPath));
```

---

## 检查清单

完成 Day2，你应该能做到：

- [ ] `PathValidator.java` 编译无错误
- [ ] `ClientSession.java` 和 `FtpServer.java` 修改完成，编译无错误
- [ ] `data/` 目录存在，包含 `public/` 和 `upload/` 子目录
- [ ] 服务器启动时显示根目录路径
- [ ] 登录后执行 `PWD` 显示 `/`
- [ ] `CWD public` 成功，`PWD` 显示 `/public`
- [ ] `CWD ..` 可以返回上级目录
- [ ] 从根目录执行 `CWD ..` 收到 550 错误（越权被阻止）
- [ ] `CWD /../../etc` 收到 550 错误（越权被阻止）
- [ ] `CWD nonexistent` 收到 550 错误（目录不存在）
- [ ] 使用绝对路径 `CWD /upload` 可以直接跳转
- [ ] 相对路径 `CWD public/../upload` 正确解析

---

## 核心概念回顾

### 为什么需要虚拟根目录？

**安全性**：防止用户访问服务器的系统文件（如 `C:\Windows`、`/etc/passwd`）。

**隔离性**：每个 FTP 用户只能在指定的 `data/` 目录内活动。

### 如何防止路径遍历攻击？

1. **规范化路径**：`normalize()` 处理 `.` 和 `..`
2. **检查前缀**：`startsWith(rootDirectory)` 确保仍在根目录内
3. **拒绝越权**：抛出 `SecurityException`，返回 550

### Path API 操作流程

```
用户输入: "public/../upload"
↓
resolve: data/public/../upload
↓
normalize: data/upload
↓
startsWith(data): ✓ 安全
↓
允许访问
```

```
用户输入: "../../etc"
↓
resolve: data/../../etc
↓
normalize: etc（或其他路径）
↓
startsWith(data): ✗ 越权
↓
拒绝访问（550）
```

---

## 下一步预告（Day3）

- 实现 `PORT` 命令：客户端告知数据端口
- 实现 `LIST` 命令：通过数据连接传输目录列表
- 学习数据连接的建立和关闭
- 理解控制连接与数据连接的关系

---

## 参考资源

- [Java NIO.2 Path 官方文档](https://docs.oracle.com/javase/8/docs/api/java/nio/file/Path.html)
- [Java Files 工具类](https://docs.oracle.com/javase/8/docs/api/java/nio/file/Files.html)
- [OWASP 路径遍历攻击](https://owasp.org/www-community/attacks/Path_Traversal)

---

## 常见问题（FAQ）

**Q：为什么 `currentWorkingDir` 用 String 而不是 Path？**  
A：因为它表示虚拟路径（相对于 FTP 根目录），不是实际的文件系统路径。在需要操作文件时，再通过 `pathValidator.resolvePath()` 转换为真实 Path。

**Q：`resolve()` 和字符串拼接有什么区别？**  
A：`resolve()` 会正确处理路径分隔符（Windows 用 `\`，Linux 用 `/`），并且返回 Path 对象可以继续操作。字符串拼接容易出错。

**Q：为什么 `normalize()` 后还要检查 `startsWith()`？**  
A：`normalize()` 只是简化路径，不会阻止越权。例如 `data/../etc` 规范化后是 `etc`，不在 `data/` 内，所以需要 `startsWith()` 检查。

**Q：如果用户输入 `CWD /`，会发生什么？**  
A：`/` 表示根目录，`resolvePath("/", "/")` 会返回 `rootDirectory`，是安全的。

**Q：`relativize()` 的作用是什么？**  
A：计算从一个路径到另一个路径的相对路径。例如 `Paths.get("/data").relativize(Paths.get("/data/upload"))` 返回 `upload`。

**Q：能否支持 Windows 风格的路径（如 `C:\data`）？**  
A：可以，`Paths.get()` 会自动识别系统路径格式。但 FTP 协议使用 Unix 风格（`/`），所以在虚拟路径中统一用 `/`。

---

**恭喜你完成 Day2！你已经掌握了 FTP 目录导航和路径安全校验的核心技术。**
