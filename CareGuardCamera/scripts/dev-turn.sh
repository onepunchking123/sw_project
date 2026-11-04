#!/bin/zsh
# 에뮬레이터 2대 실시간 영상 테스트용 TURN 중계 서버.
# 에뮬레이터는 각자 독립된 가상 네트워크(10.0.2.15)에 있어 서로 직접 연결되지 않으므로 Mac을 거쳐 중계한다.
# 값은 local.properties의 careguard.devTurn.* 를 쓴다. 실제 폰 두 대가 같은 Wi-Fi에 있으면 필요 없다.
set -euo pipefail

props="${0:A:h}/../local.properties"
prop() { grep -E "^careguard\.devTurn\.$1=" "$props" | head -1 | cut -d= -f2-; }

url="$(prop url)"
user="$(prop username)"
password="$(prop password)"
if [[ -z "$url" || -z "$user" || -z "$password" ]]; then
  echo "local.properties에 careguard.devTurn.url/username/password 가 필요합니다." >&2
  exit 1
fi

# turn:<호스트>:<포트> 에서 중계 주소를 꺼낸다. 이 주소는 두 에뮬레이터가 모두 닿을 수 있는 Mac의 LAN 주소여야 한다.
hostport="${url#turn:}"
relay_ip="${hostport%:*}"
port="${hostport##*:}"

exec turnserver -n \
  --listening-port="$port" \
  --relay-ip="$relay_ip" \
  --min-port=49160 --max-port=49200 \
  --lt-cred-mech --realm=careguard.dev --user="$user:$password" \
  --fingerprint --no-tls \
  --log-file=stdout
