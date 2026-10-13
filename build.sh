#!/bin/sh
# 使用系统工具；本工作区没有系统 JDK 25 / Maven 时使用邻接工具目录。
set -eu
project_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
if [ -z "${JAVA_HOME:-}" ] && [ -x "$project_dir/../.tools/amazon-corretto-25.jdk/Contents/Home/bin/java" ]; then
    JAVA_HOME="$project_dir/../.tools/amazon-corretto-25.jdk/Contents/Home"
    export JAVA_HOME
fi
if command -v mvn >/dev/null 2>&1; then
    mvn_bin=mvn
elif [ -x "$project_dir/../.tools/apache-maven-3.9.13/bin/mvn" ]; then
    mvn_bin="$project_dir/../.tools/apache-maven-3.9.13/bin/mvn"
else
    echo '请安装 Maven 3.9+，并将 JAVA_HOME 指向 JDK 25。' >&2
    exit 1
fi
cd "$project_dir"
if [ -d "$project_dir/../.tools/m2" ]; then
    exec "$mvn_bin" -B "-Dmaven.repo.local=$project_dir/../.tools/m2" clean package "$@"
fi
exec "$mvn_bin" -B clean package "$@"
