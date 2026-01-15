#!/bin/bash

# FTP 服务器地址
FTP_HOST="127.0.0.1"
FTP_PORT="2121"
FTP_USER="bob"
FTP_PASS="password"

# 测试脚本：使用 ftp 命令行工具进行各种操作
cat << EOF | ftp -v $FTP_HOST $FTP_PORT

USER $FTP_USER
PASS $FTP_PASS

# 测试 1: 列出根目录
TYPE A
PASV
LIST

# 测试 2: 进入 /public 目录
CWD /public
PWD
PASV
LIST

# 测试 3: 进入 /upload 目录
CWD /upload
PWD

# 测试 4: 返回根目录
CWD /
PWD

# 测试 5: 创建新目录
MKD /test_dir

# 测试 6: 进入新目录
CWD /test_dir
PWD

# 测试 7: 返回根目录
CWD /
PWD

QUIT

EOF
