$projectPath = "c:\Users\Sept_thirteen\Desktop\计算机网络课设\基于socket 的FTP设计与实现"

# 启动服务器
Write-Host "========== 启动 FTP 服务器 ==========" -ForegroundColor Green
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$projectPath'; java -cp bin data.FtpServer"
Start-Sleep -Seconds 3

# 启动客户端
Write-Host "`n========== 运行 FTP 客户端测试 ==========" -ForegroundColor Green
& java -cp $projectPath\bin data.SimpleFtpClient

Write-Host "`n========== 测试完成 ==========" -ForegroundColor Green
