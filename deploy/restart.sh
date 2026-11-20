#!/bin/bash
##############################################################
# relay（云效消息监听服务）重启脚本
#
# 放在 jar 同级目录，和 relay.jar、start.sh、stop.sh 一起
#
# 用法：
#   chmod +x restart.sh
#   ./restart.sh
#
# 只做编排：先调 stop.sh，再调 start.sh。
# 两个脚本任何一个有问题，这里直接报错，不自己实现 kill 逻辑。
##############################################################

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

STOP_SH="$APP_HOME/stop.sh"
START_SH="$APP_HOME/start.sh"

# 进程退出到端口真正释放之间有一点时间差，停完等一下再起，避免 Address already in use
PORT_RELEASE_WAIT=2

if [ ! -x "$STOP_SH" ]; then
    echo "[失败] 找不到可执行的 stop.sh：$STOP_SH"
    echo "       先执行 chmod +x stop.sh"
    exit 1
fi

if [ ! -x "$START_SH" ]; then
    echo "[失败] 找不到可执行的 start.sh：$START_SH"
    echo "       先执行 chmod +x start.sh"
    exit 1
fi

echo "=========== 1/2 停止 ==========="
"$STOP_SH"
STOP_CODE=$?

if [ $STOP_CODE -ne 0 ]; then
    echo ""
    echo "[失败] 停止没成功（退出码 $STOP_CODE），不再继续启动。"
    echo "       先手工确认进程状态：ps -ef | grep relay.jar | grep -v grep"
    exit 1
fi

echo ""
echo "等 ${PORT_RELEASE_WAIT} 秒让端口释放..."
sleep "$PORT_RELEASE_WAIT"

echo ""
echo "=========== 2/2 启动 ==========="
"$START_SH"
exit $?
