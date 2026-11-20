#!/bin/bash
##############################################################
# relay（云效消息监听服务）启动脚本（nohup 后台常驻）
#
# 放在 jar 同级目录，和下面这些文件一起：
#   /你的目录/relay/relay.jar
#   /你的目录/relay/application.yml
#   /你的目录/relay/.env
#   /你的目录/relay/start.sh
#
# 用法：
#   chmod +x start.sh
#   ./start.sh
#
# 只做启动这一件事。停止用 stop.sh，重启用 restart.sh。
# 本脚本结构照抄合并项目 deploy/start.sh，只改了服务名、jar 名、内存和验活关键字
# （设计文档 14.9：两套脚本结构一致、参数不同，比另写一套稳）。
##############################################################

# ---------- 路径 ----------
# 不管从哪个目录执行，都切到脚本所在的目录，避免相对路径的日志、配置找错位置
APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 真的切过去（上面那行只是算出路径，没有改变当前目录）。
# 必须切：application.yml 里的 spring.config.import 写的是 ./.env，
# 这个相对路径按**进程工作目录**解析。不切的话从别的目录调用本脚本，
# .env 会找错地方；又因为写了 optional:，找不到是静默跳过 —— 不报错、不告警，
# 直接用占位值，表现成"配置改了没生效"，最难查。
cd "$APP_HOME" || exit 1

APP_NAME="relay"
APP_JAR="$APP_HOME/${APP_NAME}.jar"
LOG_DIR="$APP_HOME/logs"
CONSOLE_LOG="$LOG_DIR/console.log"
PID_FILE="$APP_HOME/${APP_NAME}.pid"

# ---------- JDK ----------
# 编译产物是 Java 8 字节码，JDK8 / JDK17 都能跑。
JAVA_BIN="/root/jdk/jdk-17.0.17/bin/java"
# JAVA_BIN="/root/jdk/jdk1.8.0_291/bin/java"

# ---------- JVM 参数 ----------
# 这个服务只有 web + 文件队列，没有飞书 SDK、没有云效 SDK，128M~256M 足够
JVM_OPTS="-server -Xms128m -Xmx256m"
JVM_OPTS="$JVM_OPTS -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${LOG_DIR}"
JVM_OPTS="$JVM_OPTS -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

# ---------- 前置检查 ----------
if [ ! -x "$JAVA_BIN" ]; then
    echo "[失败] 找不到 JDK：$JAVA_BIN"
    echo "       改用 JDK8 就编辑本脚本，把 JAVA_BIN 换成 /root/jdk/jdk1.8.0_291/bin/java"
    exit 1
fi

if [ ! -f "$APP_JAR" ]; then
    echo "[失败] 找不到 jar：$APP_JAR"
    echo "       把 relay.jar 放到 $APP_HOME 下再执行"
    exit 1
fi

if [ ! -f "$APP_HOME/application.yml" ]; then
    echo "[失败] 找不到配置文件：$APP_HOME/application.yml"
    echo "       这个文件必须和 jar 同级，否则读不到队列目录、端口等配置"
    echo "       从仓库复制一份：cp src/main/resources/application.yml $APP_HOME/"
    exit 1
fi

# .env 放的是本机专属的真实值（云效密钥 + 目标地址），application.yml 里只有 ${} 占位。
# 缺了它不影响启动流程，但密钥会回落成占位值、所有推送都会被 403。
# 所以这里只提示、不退出 —— 凭据也可能是通过系统环境变量提供的。
if [ ! -f "$APP_HOME/.env" ]; then
    echo "[提示] 没有找到 $APP_HOME/.env"
    echo "       凭据若是用系统环境变量提供的，忽略这条；"
    echo "       否则云效推送会一直 403，模板见同目录 .env.example"
    echo ""
fi

# 已经在跑就别重复启动，否则两个进程抢同一个端口，日志也会混在一起
if [ -f "$PID_FILE" ]; then
    OLD_PID="$(cat "$PID_FILE")"
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[跳过] 已经在运行，PID=$OLD_PID"
        echo "       要重启：./restart.sh"
        exit 0
    fi
    echo "[提示] 残留的 pid 文件（进程已不在），继续启动"
    rm -f "$PID_FILE"
fi

# pid 文件被手动删掉时，再用进程名兜底查一遍，避免起两份抢同一个端口。
# 注意这里匹配的是 relay.jar，不会误匹配合并项目的 merge-bot.jar（设计文档 14.9 第 3 点）
if command -v pgrep >/dev/null 2>&1; then
    RUN_PID="$(pgrep -f "$APP_JAR" | head -1)"
    if [ -n "$RUN_PID" ]; then
        echo "[跳过] 检测到已在运行的进程，PID=$RUN_PID（pid 文件丢了，顺手补上）"
        echo "$RUN_PID" > "$PID_FILE"
        echo "       要重启：./restart.sh"
        exit 0
    fi
fi

mkdir -p "$LOG_DIR"

# ---------- 启动 ----------
echo "----------------------------------------"
echo " 启动目录 : $APP_HOME"
echo " 使用 JDK : $JAVA_BIN"
echo " 日志目录 : $LOG_DIR"
echo "----------------------------------------"

# 后台常驻。控制台输出单独留一份，启动阶段报错（logback 还没生效时）只能从这里看。
# --spring.config.additional-location 加载外置 application.yml，优先级最高（设计文档 8.3 第 2 点）
nohup "$JAVA_BIN" $JVM_OPTS \
    -DLOG_PATH="$LOG_DIR" \
    -jar "$APP_JAR" \
    --spring.config.additional-location=file:"$APP_HOME"/ \
    > "$CONSOLE_LOG" 2>&1 &

PID=$!
echo "$PID" > "$PID_FILE"

# 给 Spring Boot 启动留点时间，再看进程还在不在
sleep 8

if kill -0 "$PID" 2>/dev/null; then
    echo "[成功] 已启动，PID=$PID"
    echo ""
    echo " 看日志   : tail -f $LOG_DIR/${APP_NAME}-info.log"
    echo " 看报错   : tail -f $LOG_DIR/${APP_NAME}-error.log"
    echo " 启动输出 : tail -f $CONSOLE_LOG"
    echo " 健康检查 : curl http://127.0.0.1:18123/health"
    echo " 停止服务 : $APP_HOME/stop.sh"
    echo " 重启服务 : $APP_HOME/restart.sh"
    echo " 验证存活 : grep -a \"Started RelayApplication\" $LOG_DIR/${APP_NAME}-info.log"
else
    echo "[失败] 进程已退出，最后 40 行启动日志："
    echo "----------------------------------------"
    tail -n 40 "$CONSOLE_LOG"
    echo "----------------------------------------"
    rm -f "$PID_FILE"
    exit 1
fi
