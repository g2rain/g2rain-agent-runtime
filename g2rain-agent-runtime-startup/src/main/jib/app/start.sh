#!/bin/sh
# Runtime 启动脚本：在 Jib 生成的 classpath 后追加 /app/plugins 下每个插件子目录的 jar。
# JVM 参数走 JAVA_TOOL_OPTIONS；容器 command 追加的参数原样传给应用。
CP=$(cat /app/jib-classpath-file)
for dir in /app/plugins/*/; do
  [ -d "$dir" ] || continue
  CP="$CP:${dir}*"
done
exec java -cp "$CP" "$(cat /app/jib-main-class-file)" "$@"
