package data;

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
    // 构造方法，传入根目录路径目的是构造根目录
    public PathValidator(String rootPath) throws IOException {
        // 将字符串路径转换为 Path 对象
        Path root = Paths.get(rootPath);
        
        // 转换为绝对路径并规范化（去除多余的 . 和 ..）
        this.rootDirectory = root.toAbsolutePath().normalize();
        
        // 检查根目录是否存在,不存在就抛出异常
        if (!Files.exists(this.rootDirectory)) {
            throw new IOException("Root directory does not exist: " + this.rootDirectory);
        }
        
        // 检查是否为目录，不是目录就抛出异常
        if (!Files.isDirectory(this.rootDirectory)) {
            throw new IOException("Root path is not a directory: " + this.rootDirectory);
        }
        
        System.out.println("[PathValidator] FTP 根目录设置为: " + this.rootDirectory);
    }
    
    /**
     * 获取 FTP 虚拟根目录
     */
    public Path getRootDirectory() {
        // 返回根目录,
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
        
        // 1. 构建当前工作目录的实际路径
        // currentWorkingDir 是相对于根目录的，例如 "/upload" 或 "/"
        Path currentDir;// 当前工作目录的绝对路径

        if (currentWorkingDir.equals("/") || currentWorkingDir.isEmpty()) {
            // 如果当前工作目录为根目录，则直接使用 rootDirectory
            currentDir = rootDirectory;
        } else {
            // 去掉前导 /，因为 resolve 不需要它
            String cleanCwd = currentWorkingDir.startsWith("/") 
                ? currentWorkingDir.substring(1) 
                : currentWorkingDir;
                
            // 将 cleanCwd 转换为 Path 对象
            currentDir = rootDirectory.resolve(cleanCwd);
        }
        
        // 2. 处理用户输入路径，去掉字符串开头和结尾的空白字符
        userInputPath = userInputPath.trim();
        // 如果用户输入以 / 开头，表示从根目录开始（绝对路径）
        if (userInputPath.startsWith("/")) {
            // 去掉前导 /，因为我们会基于 rootDirectory 解析
            userInputPath = userInputPath.substring(1);
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
     * @return 虚拟 FTP 路径（总是以 / 开头）(传String是因为在主函数中使用的全局变量是String类型)
     */
    public String toVirtualPath(Path absolutePath) {
        // 规范化路径，处理多余的 . 和 ..
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