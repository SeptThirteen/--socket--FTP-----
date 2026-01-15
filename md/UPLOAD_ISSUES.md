# FTP 上传失败诊断与解决（常见：文件被占用 / 文件过大）

概要
- 本文档汇总了常见的 FTP 上传失败原因、诊断步骤与解决办法，适用于 Windows + 自实现 Java FTP 服务器（基于本项目）。
- 常见报错示例：
  - 客户端错误：Error EElVFSAdapterError: File system operation failed with error 103426. Requested path is C:\Users\\Sept_thirteen\\Downloads\\提升清晰度.mp4
  - 服务器日志：
    - [ClientSession] 文件上传失败: java.io.IOException - <message>
    - [DataConnection] 已接收 X 字节 / 已发送 Y 字节

症状
- 小文件（如 .txt、.jpg、.mp3）能正常上传，大文件（.mp4、.docx、.pdf）可能失败
- 出现 426/550 等数据传输或文件系统错误
- 服务器端出现写入异常或 socket 超时

主要原因（按概率排序）
1. 文件被其他程序占用（Windows 常见）
   - Office 文档在 Word/Excel 打开时被锁定
   - 系统的“预览窗格”或媒体播放器会短时间持有句柄
   - 杀毒软件/索引服务会暂时打开文件扫描
2. 数据连接或写入超时（文件太大，默认超时太短）
   - 被动模式 ServerSocket 超时（本项目默认已修改为 300 秒）
3. 文件权限或磁盘空间不足
4. 文件名编码或特殊字符问题（UTF-8/GBK 问题）：较少见，但需要关注中文/特殊字符
5. 防火墙或网络中断导致数据流中断

诊断步骤（按顺序）
1. 在服务器端查看日志（最重要）
   - 关注像下面的日志行：
     - [ClientSession] 准备写入文件: C:\...\提升清晰度.mp4
     - [ClientSession] 文件输出流已创建，开始接收数据...
     - [ClientSession] 文件上传失败: java.io.IOException - <详细信息>
     - 异常栈跟踪（我们在代码中已增加 e.printStackTrace()）
2. 确认本地文件是否被占用（Windows）
   - 使用 Resource Monitor → CPU → Associated Handles，搜索文件名
   - 或使用 Sysinternals 工具：`handle.exe "提升清晰度.mp4"`（需管理员）
   - 或使用 PowerShell（管理员）：`openfiles /query`（需要开启全局打开文件跟踪）
3. 检查磁盘空间与权限
   - PowerShell: `Get-PSDrive -PSProvider FileSystem` 或 `dir` / `Properties` 检查剩余空间
   - 确保 FTP 服务运行用户对目标目录有写权限
4. 检查杀毒软件/防火墙记录
   - 临时禁用或添加上传目录为例外再试
5. 通过分段或小文件测试网络稳定性与速度
   - 上传一个已知很大的文件并观察接收速率
6. 在服务器端确认数据连接超时设置
   - 本项目：`passiveServerSocket.setSoTimeout(300000);`（已改为 300s）

快速修复建议
- 在客户端：关闭目标文件的任何打开程序；关闭 Windows 资源管理器的预览窗格
- 在服务器：增加被动模式超时（已修改为 300s）；确保磁盘空间充足；检查文件权限
- 若问题频繁发生：暂时在服务器端关闭或排除杀毒软件对上传目录的扫描

代码级别改进（已在项目中改动）
- 增加超时：
```java
passiveServerSocket.setSoTimeout(300000); // 300 秒
```
- 增强日志：
```text
[ClientSession] 准备写入文件: <path>
[ClientSession] 父目录可写: true/false
[ClientSession] 文件输出流已创建，开始接收数据...
[ClientSession] 文件上传失败: <ExceptionClass> - <message>
```
- 在写入失败时删除不完整文件以避免残留：
```java
if (Files.exists(filePath)) Files.delete(filePath);
```

如何在本项目中复现并排查
1. 启动服务器：
   - cd bin && java data.FtpServer
2. 使用 MobaXterm 或 Windows 资源管理器上传一个大文件（如 >100MB 的 .mp4）
3. 若失败，立即查看服务器控制台日志，复制异常信息
4. 在服务器上运行 `Get-ChildItem <data 目录>` 查看是否已创建临时或不完整文件
5. 用 `handle.exe` 检查客户端是否还占用该文件

长期改进建议（可作为后续任务）
- 支持 REST/REST STREAM 分段续传以提高大文件鲁棒性
- 支持上传速率限制与流量监控，防止单连接耗尽资源
- 提供更友好的客户端重试与断点续传功能
- 集成进度日志和更详细的错误码（用于 UI 显示）

附录：常见命令 & 工具（Windows）
- 检查进程打开句柄：Sysinternals handle.exe
- 查询打开文件：`openfiles /query`（需管理员）
- 查看磁盘空间：`Get-PSDrive -PSProvider FileSystem`
- 查看端口占用：`netstat -ano | findstr ":2121"`

---
如需，我可以：
- 将此文档链接到 `README.md` 的常见问题部分；
- 将部分诊断步骤加入服务器自动日志（例如在上传失败时写入更结构化的日志文件）。

> 文件已保存为 `md/UPLOAD_ISSUES.md`。欢迎告诉我是否需要加例子截图或进一步本地化步骤。