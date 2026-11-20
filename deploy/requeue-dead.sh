#!/bin/bash
##############################################################
# 死信补发工具（设计文档 14.16）
#
# 用法：
#   ./requeue-dead.sh queue/dead/a1b2c3d4-0001_1763604612000_20261120093012_auth.json
#
# 批量补发自己写 for 循环（本脚本刻意不做批量逻辑）：
#   for f in queue/dead/*_auth_*.json; do ./requeue-dead.sh "$f"; done
#
# 它只做一件事：把一个死信文件放回待发队列
#   1. 校验这个文件确实在 dead/ 目录里，文件名符合 dead 规则
#   2. 从文件名解析出投递编号（截到首次到达毫秒之前那一段）
#   3. 改名成 pending/{编号}_{当前毫秒}.json —— 次数归零、原因标记去掉
#   4. mv 过去，并 touch 成当前时间
#   5. 打印一行结果（成功失败都能看见）
#
# 为什么必须改名而不是直接 mv：
#   从 dead 搬回来的文件带着 _进死信时间_原因 三段尾巴，且"已试次数"已经是最大值，
#   直接丢回 pending 会在第一次失败时立刻又进死信，等于补发永远不成功（设计文档 5.8）。
#
# 为什么不加 POST /admin/requeue 接口：
#   监听服务的 18123 是对云效开放的公网端口，加管理接口就得再配一套鉴权；
#   脚本只在服务器本机跑，没有暴露面，而且 ls 加一行循环就能看清补发了什么。
##############################################################

set -u

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ $# -ne 1 ]; then
    echo "用法: $0 queue/dead/<文件名>.json"
    echo "例  : $0 queue/dead/a1b2c3d4-0001_1763604612000_20261120093012_auth.json"
    exit 1
fi

SOURCE="$1"

if [ ! -f "$SOURCE" ]; then
    echo "[失败] 文件不存在：$SOURCE"
    exit 1
fi

# ---------- 队列目录 ----------
# 优先用 .env 里的 RELAY_QUEUE_DIR（相对路径按本脚本所在目录解析），否则用默认 ./queue
QUEUE_DIR="$APP_HOME/queue"
if [ -f "$APP_HOME/.env" ]; then
    ENV_QUEUE_DIR="$(grep -E '^[[:space:]]*RELAY_QUEUE_DIR=' "$APP_HOME/.env" | tail -1 | cut -d= -f2- | tr -d '\r')"
    if [ -n "${ENV_QUEUE_DIR:-}" ]; then
        case "$ENV_QUEUE_DIR" in
            /*) QUEUE_DIR="$ENV_QUEUE_DIR" ;;
            *)  QUEUE_DIR="$APP_HOME/$ENV_QUEUE_DIR" ;;
        esac
    fi
fi

SOURCE_DIR="$(cd "$(dirname "$SOURCE")" && pwd)"
DEAD_DIR="$(cd "$QUEUE_DIR/dead" 2>/dev/null && pwd)"

if [ -z "${DEAD_DIR:-}" ] || [ "$SOURCE_DIR" != "$DEAD_DIR" ]; then
    echo "[失败] 只能补发 dead/ 目录里的文件"
    echo "       你给的是：$SOURCE"
    echo "       dead 目录是：$QUEUE_DIR/dead"
    echo "       先用 ls -1 $QUEUE_DIR/dead/*_auth_*.json 确认文件位置"
    exit 1
fi

# ---------- 解析文件名 ----------
# dead 文件名：{编号}_{首次到达毫秒}_{进死信时间}_{原因}.json
# 编号本身可能带下划线，所以从右往左切三段，剩下的整段就是编号
NAME="$(basename "$SOURCE")"
case "$NAME" in
    *.json) ;;
    *) echo "[失败] 不是 .json 文件：$NAME"; exit 1 ;;
esac

BASE="${NAME%.json}"
TRIMMED_1="${BASE%_*}"          # 去掉原因
REASON="${BASE##*_}"
TRIMMED_2="${TRIMMED_1%_*}"     # 去掉进死信时间
DEAD_AT="${TRIMMED_1##*_}"
DELIVERY_ID="${TRIMMED_2%_*}"   # 去掉首次到达毫秒
ARRIVED_AT="${TRIMMED_2##*_}"

if [ -z "$DELIVERY_ID" ] || [ "$DELIVERY_ID" = "$TRIMMED_2" ]; then
    echo "[失败] 文件名不符合 dead 规则（{编号}_{到达毫秒}_{进死信时间}_{原因}.json）：$NAME"
    exit 1
fi
case "$ARRIVED_AT" in
    ''|*[!0-9]*) echo "[失败] 文件名里的首次到达毫秒不是数字：$ARRIVED_AT"; exit 1 ;;
esac
case "$DEAD_AT" in
    ''|*[!0-9]*) echo "[失败] 文件名里的进死信时间不是数字：$DEAD_AT"; exit 1 ;;
esac
case "$DELIVERY_ID" in
    *[!A-Za-z0-9_-]*) echo "[失败] 编号含非法字符（只允许字母、数字、短横线、下划线）：$DELIVERY_ID"; exit 1 ;;
esac

# ---------- 放回待发队列 ----------
NOW="$(date +%s%3N)"
PENDING_DIR="$QUEUE_DIR/pending"
mkdir -p "$PENDING_DIR" || exit 1

TARGET="$PENDING_DIR/${DELIVERY_ID}_${NOW}.json"

if [ -e "$TARGET" ]; then
    echo "[失败] 目标文件已存在（同一毫秒重复补发？）：$TARGET"
    exit 1
fi

if ! mv "$SOURCE" "$TARGET"; then
    echo "[失败] 移动文件失败：$SOURCE → $TARGET"
    exit 1
fi

# 修改时间设成当前：pending 里的 mtime 只表示"什么时候可以动它"，不影响年龄判断
touch "$TARGET" 2>/dev/null

echo "[成功] 已放回待发队列：$(basename "$TARGET")"
echo "       编号 $DELIVERY_ID（原原因 $REASON，次数已归零，首次到达时间已重置为现在）"
