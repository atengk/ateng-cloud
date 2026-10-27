#!/usr/bin/env bash
# ==============================================================================
# 鍏ㄧ敓鍛藉懆鏈熷彂鐗堥槻鍛嗚剼鏈?(Release Automation Script)
#
# 鎻愪緵涓ヨ皑鐨勫墠缃嚜妫€ (Pre-flight)銆佽涔夊寲鐗堟湰鎺ㄨ崘銆佹紨缁冩ā寮忎笌鐢熸€佹墿灞曟彃妲姐€?#
# @author Ateng
# @since 2026-10-08
# ==============================================================================

set -eo pipefail

# ==============================================================================
# 璇█鐢熸€佷笌鐗堟湰鍙烽€掑鎵╁睍鎻掓Ы (Version Bump Hook Slot)
#
# 鑻?downstream 鍏蜂綋椤圭洰闇€瑕佽嚜鍔ㄦ洿鏂板伐绋嬫枃浠剁増鏈彿锛岃В闄や笅鏂瑰搴旇瑷€鐨勪竴琛岄厤缃紝
# 鎴栧湪 custom_bump_version 鍑芥暟涓紪鍐欒嚜瀹氫箟鍛戒护銆備紶鍏ュ弬鏁?$1 涓轰笉甯?v 鍓嶇紑鐨勭増鏈彿锛堝 1.2.0锛夈€?# ==============================================================================
# shellcheck disable=SC2034
custom_bump_version() {
  local RAW_VERSION="$1"   # 绾増鏈彿锛屽 "1.2.0"
  local FULL_VERSION="v$1" # 甯?v 鍓嶇紑鐨勫畬鏁存爣绛撅紝濡?"v1.2.0"
  : "${RAW_VERSION}" "${FULL_VERSION}"

  # 1. Java (Maven) POM 鐗堟湰鍏ㄩ噺鏇存柊
  log_info "姝ｅ湪鎵ц Maven versions:set 鏇存柊鍏ㄩ儴 POM 鐗堟湰鑷?$RAW_VERSION..."
  mvn versions:set -DnewVersion="$RAW_VERSION" -DgenerateBackupPoms=false

  # 2. 鑷姩鍚屾鏇存柊闂ㄩ潰涓?BOM 鎺ュ叆鏂囨。涓殑渚濊禆鐗堟湰 (鍩轰簬 sed -i 鐩存帴鍘熷湴淇敼锛岄浂涓存椂鏂囦欢锛岄浂娉ㄩ噴姹℃煋)
  local README_FILES=("README.md" "ateng-cloud-bom/README.md")
  local SED_OPTS=(-i -E)
  sed --version >/dev/null 2>&1 || SED_OPTS=(-i '' -E)

  for readme in "${README_FILES[@]}"; do
    if [ -f "$readme" ]; then
      log_info "姝ｅ湪鐩存帴鍘熷湴鍚屾鏇存柊 $readme 鎺ュ叆渚濊禆鐗堟湰鑷?$RAW_VERSION..."
      # 缁熶竴浣跨敤 '#' 浣滀负鍒嗛殧绗︼紝褰诲簳瑙勯伩 XML 鏂滄潬涓庢鍒欑閬撶 '|' 鐨勮В鏋愬啿绐?      sed "${SED_OPTS[@]}" "/<artifactId>ateng-cloud-(all|bom)<\/artifactId>/{n;s#<version>[^<]+</version>#<version>${RAW_VERSION}</version>#}" "$readme"
      sed "${SED_OPTS[@]}" "s#(io\.github\.atengk:ateng-cloud-(all|bom)):[0-9A-Za-z.-]+#\1:${RAW_VERSION}#g" "$readme"
    fi
  done

  return 0
}

# 缁堢鑹插僵瀹氫箟锛堝湪鏀寔鐨勭粓绔笅鎻愪緵瑙嗚鍖哄垎锛屽吋瀹归潪 TTY 闄嶇骇锛?if [ -t 1 ]; then
  COLOR_RESET="\033[0m"
  COLOR_BOLD="\033[1m"
  COLOR_GREEN="\033[32m"
  COLOR_BLUE="\033[34m"
  COLOR_YELLOW="\033[33m"
  COLOR_RED="\033[31m"
  COLOR_CYAN="\033[36m"
else
  COLOR_RESET=""
  COLOR_BOLD=""
  COLOR_GREEN=""
  COLOR_BLUE=""
  COLOR_YELLOW=""
  COLOR_RED=""
  COLOR_CYAN=""
fi

log_info() {
  printf "%b[INFO]%b %s\n" "${COLOR_BLUE}" "${COLOR_RESET}" "$1"
}

log_success() {
  printf "%b[SUCCESS]%b %s\n" "${COLOR_GREEN}" "${COLOR_RESET}" "$1"
}

log_warn() {
  printf "%b[WARN]%b %s\n" "${COLOR_YELLOW}" "${COLOR_RESET}" "$1"
}

log_error() {
  printf "%b[ERROR]%b %s\n" "${COLOR_RED}" "${COLOR_RESET}" "$1"
}

# 鍙傛暟瑙ｆ瀽
DRY_RUN=false
TARGET_VERSION=""
AUTO_CONFIRM=false

for arg in "$@"; do
  case "$arg" in
    -n|--dry-run)
      DRY_RUN=true
      ;;
    -y|--yes)
      AUTO_CONFIRM=true
      ;;
    -h|--help)
      printf "%b\n" "鐢ㄦ硶: bash scripts/release.sh [鐗堟湰鍙穄 [閫夐」]"
      printf "%b\n" ""
      printf "%b\n" "鍙傛暟璇存槑:"
      printf "%b\n" "  [鐗堟湰鍙穄      鍙€夈€傜鍚?SemVer 鏍煎紡鐨勬爣绛惧悕锛堝 v1.0.0锛夈€傝嫢鏈彁渚涗笖澶勪簬浜や簰缁堢鍒欏惎鍔ㄥ悜瀵笺€?
      printf "%b\n" "  -y, --yes     鍙€夈€傝烦杩囨渶缁堢‘璁ゆ彁绀猴紝鐩存帴鎵ц鍙戠増锛堥€傚悎 CI 鎴?AI 鑷姩鍖栬皟鐢級銆?
      printf "%b\n" "  -n, --dry-run 鍙€夈€傛紨缁冩ā寮忥紝浠呮墽琛屾鏌ュ苟棰勮鎷熸墽琛屽懡浠わ紝涓嶄骇鐢熶换浣曠湡瀹?Git 鍙樻洿銆?
      printf "%b\n" "  -h, --help    鏄剧ず甯姪淇℃伅銆?
      printf "%b\n" ""
      printf "%b\n" "鏃犲弬鏁拌繍琛岀ず渚?(浜や簰鍚戝妯″紡):"
      printf "%b\n" "  bash scripts/release.sh"
      printf "%b\n" ""
      printf "%b\n" "闈炰氦浜掑紡璋冪敤绀轰緥 (AI / 鑷姩鍖栨墽琛?:"
      printf "%b\n" "  bash scripts/release.sh v1.4.0 -y"
      printf "%b\n" "  bash scripts/release.sh v1.4.0 --dry-run"
      exit 0
      ;;
    v*)
      TARGET_VERSION="$arg"
      ;;
    *)
      if [[ "$arg" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
        TARGET_VERSION="v$arg"
      else
        log_error "鏃犳硶璇嗗埆鐨勫弬鏁? $arg (鐗堟湰鍙峰繀椤讳互 v 寮€澶存垨涓烘暟瀛楃増鏈紝濡?v1.0.0锛涢€夐」鏀寔 -y/--yes, -n/--dry-run, -h/--help)"
        exit 1
      fi
      ;;
  esac
done

printf "\n%b\n" "${COLOR_BOLD}${COLOR_CYAN}================================================================${COLOR_RESET}"
printf "%b\n" "${COLOR_BOLD}${COLOR_CYAN}         OSS 鑷姩鍖栧彂鐗堥槻鍛嗚嚜妫€绋嬪簭 (Release Guard)              ${COLOR_RESET}"
printf "%b\n" "${COLOR_BOLD}${COLOR_CYAN}================================================================${COLOR_RESET}"

if [ "$DRY_RUN" = true ]; then
  printf "%b\n\n" "${COLOR_BOLD}${COLOR_YELLOW}>>> 銆愭紨缁冩ā寮?DRY-RUN 宸叉縺娲汇€戜互涓嬫搷浣滀粎鍋氬畨鍏ㄩ瑙堬紝涓嶄慨鏀逛换浣?Git 鐘舵€?<<<\n${COLOR_RESET}"
fi

# ==============================================================================
# 1. 鍓嶇疆闃插憜鑷 (Pre-flight Checks)
# ==============================================================================
log_info "姝ｅ湪鎵ц鍙戠増鍓嶇疆鐜鑷 (Pre-flight Checks)..."

# 1.1 妫€鏌?Git 宸ュ叿
if ! command -v git >/dev/null 2>&1; then
  log_error "鏈娴嬪埌 git 鍛戒护锛岃鍏堝畨瑁?Git銆?
  exit 1
fi

# 1.2 妫€鏌ユ槸鍚﹀湪 Git 宸ヤ綔鍖哄苟鑷姩瀹氫綅鑷充粨搴撴牴鐩綍
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  log_error "褰撳墠鐩綍涓嶅湪 Git 浠撳簱鍐咃紝璇峰湪 Git 浠撳簱鍐呮墽琛屻€?
  exit 1
fi
REPO_ROOT=$(git rev-parse --show-toplevel)
cd "$REPO_ROOT"

# 1.3 鏍￠獙褰撳墠鍒嗘敮鏄惁涓洪粯璁や富骞插垎鏀?(main 鎴?master)
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$CURRENT_BRANCH" != "main" ] && [ "$CURRENT_BRANCH" != "master" ]; then
  log_error "褰撳墠澶勪簬鍒嗘敮 [$CURRENT_BRANCH]锛屼弗绂佸湪闈炰富骞插垎鏀彂鐗堬紒璇峰厛鍒囨崲鑷?main 鍒嗘敮: git checkout main"
  exit 1
fi
log_success "褰撳墠澶勪簬鍚堣涓诲共鍒嗘敮: $CURRENT_BRANCH"

# 1.4 鏍￠獙宸ヤ綔鍖烘槸鍚﹀共鍑€ (鑴忎唬鐮佹嫤鎴?
DIRTY_FILES=$(git status --porcelain)
if [ -n "$DIRTY_FILES" ]; then
  log_error "妫€娴嬪埌鏈湴宸ヤ綔鍖哄瓨鍦ㄦ湭鎻愪氦鐨勫彉鏇存垨鏈窡韪枃浠讹紒"
  printf "%b\n" "鑴忔枃浠跺垪琛?"
  printf "%s\n" "$DIRTY_FILES"
  log_error "涓洪槻姝㈡湭缁忛獙璇佺殑浠ｇ爜闅忕増鏈彂鐗堬紝鍙戠増鍓嶅伐浣滃尯蹇呴』缁濆骞插噣锛?
  log_info "鎻愮ず: 浣犲彲浠ュ厛杩愯 'bash scripts/commit.sh' 瑙勮寖鍖栨彁浜ゅ彉鏇达紝鎴栦娇鐢?'git stash' 鏆傚瓨宸ヤ綔鍖恒€?
  exit 1
fi
log_success "宸ヤ綔鍖虹姸鎬佹鏌ラ€氳繃: 骞插噣鏃犳湭鎻愪氦浠ｇ爜"

# 1.5 鏍￠獙涓庤繙绔粨搴撳悓姝ョ姸鎬?(闃叉紡鎷夎繙绔唬鐮佷笌瓒呭墠鎻愪氦妫€娴?
log_info "姝ｅ湪鎷夊彇杩滅鏈€鏂扮姸鎬?(git fetch origin)..."
AHEAD_COUNT=0
if git fetch origin >/dev/null 2>&1; then
  BEHIND_COUNT=$(git rev-list "HEAD..origin/$CURRENT_BRANCH" --count 2>/dev/null || echo "0")
  if [ "$BEHIND_COUNT" -gt 0 ]; then
    log_error "鏈湴鍒嗘敮钀藉悗浜庤繙绔?origin/$CURRENT_BRANCH ($BEHIND_COUNT 涓彁浜ゆ湭鍚堝苟)锛?
    log_error "鍙戠増鍓嶅繀椤诲悎骞惰繙绔渶鏂颁唬鐮侊紝璇峰厛鎵ц: git pull origin $CURRENT_BRANCH"
    exit 1
  fi
  AHEAD_COUNT=$(git rev-list "origin/$CURRENT_BRANCH..HEAD" --count 2>/dev/null || echo "0")
  if [ "$AHEAD_COUNT" -gt 0 ]; then
    log_warn "妫€娴嬪埌鏈湴涓诲共鍒嗘敮瓒呭墠浜庤繙绔?($AHEAD_COUNT 涓彁浜ゆ湭鎺ㄩ€?銆傚彂鐗堟祦绋嬪皢鍦ㄦ墦鏍囩鍓嶈嚜鍔ㄦ帹閫佷富骞插垎鏀€?
  else
    log_success "涓庤繙绋嬪垎鏀姸鎬佸悓姝? 鏈湴涓诲共涓庤繙绔畬鍏ㄤ竴鑷?
  fi
else
  log_warn "鏃犳硶杩炴帴杩滅▼浠撳簱杩涜鍚屾鏍￠獙锛堝彲鑳藉浜庣绾跨幆澧冿級锛岃烦杩囪繙绔媺鍙栨牎楠屻€?
fi

# 1.6 澶氭ā鍧椾唬鐮佸彲缂栬瘧鎬ч妫€ (闃插甫缂栬瘧閿欒浠ｇ爜鍙戠増鎵撹剰鏍囩)
log_info "姝ｅ湪鎵ц宸ョ▼澶氭ā鍧楃紪璇戝揩閫熻嚜妫€ (mvn test-compile -q -T 1C -nsu)..."
if ! mvn test-compile -q -T 1C -nsu; then
  log_error "鏈湴浠ｇ爜缂栬瘧澶辫触锛佷弗绂佸皢瀛樺湪缂栬瘧閿欒鐨勮剰浠ｇ爜鎺ㄥ悜杩滅▼鍙戠増锛?
  log_error "璇峰厛鎺掓煡淇鏈湴缂栬瘧閿欒鍚庨噸璇曘€?
  exit 1
fi
log_success "澶氭ā鍧楃紪璇戣嚜妫€閫氳繃: 浠ｇ爜鍙甯哥紪璇?

# ==============================================================================
# 2. 鐗堟湰鍙疯В鏋愪笌璇箟鍖栨帹瀵?# ==============================================================================
# 鑾峰彇鏈€杩戜竴娆″巻鍙?Git Tag (浼樺厛鍖归厤鏍囧噯 v* 鏍囩锛涜嫢瀛橀噺椤圭洰鏈甫 v 鍒欏吋瀹瑰尮閰嶇函鏁板瓧鏍囩)
LATEST_TAG=$(git describe --tags --abbrev=0 --match "v[0-9]*" 2>/dev/null || echo "")
if [ -z "$LATEST_TAG" ]; then
  LATEST_TAG=$(git describe --tags --abbrev=0 --match "[0-9]*" 2>/dev/null || echo "")
fi

if [ -z "$LATEST_TAG" ]; then
  log_info "鏈娴嬪埌鍘嗗彶鐗堟湰 Tag锛屽皢鍩轰簬鍒濆鐗堟湰 v0.0.0 鎺ㄥ銆?
  BASE_VERSION="v0.0.0"
  V_MAJOR=0
  V_MINOR=0
  V_PATCH=0
else
  if [[ "$LATEST_TAG" =~ ^[0-9] ]]; then
    log_info "妫€娴嬪埌瀛橀噺椤圭洰鍘嗗彶鐗堟湰 Tag锛堟湭甯?v 鍓嶇紑锛? $LATEST_TAG锛屾湰娆″彂鐗堝皢鑷姩瀵归綈涓烘爣鍑?SemVer (v$LATEST_TAG)"
    BASE_VERSION="v$LATEST_TAG"
  else
    log_info "妫€娴嬪埌褰撳墠鏈€鏂扮増鏈?Tag: $LATEST_TAG"
    BASE_VERSION="$LATEST_TAG"
  fi
  # 鎻愬彇鐗堟湰鍙锋暟瀛楅儴鍒?(鍘绘帀 v 鍓嶇紑浠ュ強鍙兘鐨勯鍙戝竷鍚庣紑涓庢瀯寤哄厓鏁版嵁)
  CLEAN_VER=$(echo "${BASE_VERSION#v}" | cut -d'-' -f1 | cut -d'+' -f1)
  V_MAJOR=$(echo "$CLEAN_VER" | cut -d'.' -f1)
  V_MINOR=$(echo "$CLEAN_VER" | cut -d'.' -f2)
  V_PATCH=$(echo "$CLEAN_VER" | cut -d'.' -f3)

  # 鏁板瓧瀹归敊淇濆簳
  V_MAJOR=${V_MAJOR:-0}
  V_MINOR=${V_MINOR:-0}
  V_PATCH=${V_PATCH:-0}
fi

NEXT_PATCH="v${V_MAJOR}.${V_MINOR}.$((V_PATCH + 1))"
NEXT_MINOR="v${V_MAJOR}.$((V_MINOR + 1)).0"
NEXT_MAJOR="v$((V_MAJOR + 1)).0.0"

# 鑻ユ湭閫氳繃鍛戒护琛屼紶鍏ョ増鏈彿锛屽惎鍔ㄤ氦浜掑紡閫夋嫨鍚戝
if [ -z "$TARGET_VERSION" ]; then
  if [ ! -t 0 ]; then
    log_error "闈炰氦浜掑紡鐜涓嬪繀椤绘樉寮忔彁渚涚増鏈彿鍙傛暟锛佷緥濡? bash scripts/release.sh v1.4.0 -y"
    exit 1
  fi

  printf "\n%b\n" "${COLOR_BOLD}璇烽€夋嫨鏈鍙戝竷鐨勭洰鏍囩増鏈彿 (Semantic Versioning):${COLOR_RESET}"
  printf "  %b0)%b 閫€鍑哄彂鐗堟祦绋?(Quit / Exit)\n" "${COLOR_YELLOW}" "${COLOR_RESET}"
  printf "  %b1)%b Patch  (馃悰 琛ヤ竵淇 / 缂洪櫡淇)   : %b%s%b\n" "${COLOR_GREEN}" "${COLOR_RESET}" "${COLOR_CYAN}" "$NEXT_PATCH" "${COLOR_RESET}"
  printf "  %b2)%b Minor  (馃殌 鏂板姛鑳藉紩鍏?/ 鍚戜笅鍏煎) : %b%s%b\n" "${COLOR_GREEN}" "${COLOR_RESET}" "${COLOR_CYAN}" "$NEXT_MINOR" "${COLOR_RESET}"
  printf "  %b3)%b Major  (馃挜 鐮村潖鎬ф洿鏂?/ 鏋舵瀯澶ф敼) : %b%s%b\n" "${COLOR_GREEN}" "${COLOR_RESET}" "${COLOR_CYAN}" "$NEXT_MAJOR" "${COLOR_RESET}"
  printf "  %b4)%b Custom (鉁嶏笍  鑷畾涔夎緭鍏ョ増鏈彿锛屽 pre-release)\n\n" "${COLOR_GREEN}" "${COLOR_RESET}"

  CHOICE=""
  while [ -z "$TARGET_VERSION" ]; do
    printf "%b璇疯緭鍏ラ€夐」 [0-4]: %b" "${COLOR_BOLD}" "${COLOR_RESET}"
    read -r CHOICE
    CHOICE=$(echo "$CHOICE" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    case "$CHOICE" in
      0|[qQ]|[eE][xX][iI][tT])
        log_info "宸插彇娑堝彂鐗堛€?
        exit 0
        ;;
      1) TARGET_VERSION="$NEXT_PATCH" ;;
      2) TARGET_VERSION="$NEXT_MINOR" ;;
      3) TARGET_VERSION="$NEXT_MAJOR" ;;
      4)
        printf "%b璇疯緭鍏ヨ嚜瀹氫箟鐗堟湰鍙?(闇€绗﹀悎 SemVer锛屽 v1.2.3 鎴?v1.2.3-beta.1): %b" "${COLOR_BOLD}" "${COLOR_RESET}"
        read -r CUSTOM_VER
        CUSTOM_VER=$(echo "$CUSTOM_VER" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        if [[ "$CUSTOM_VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
          CUSTOM_VER="v$CUSTOM_VER"
        fi
        TARGET_VERSION="$CUSTOM_VER"
        ;;
      *)
        log_warn "鏃犳晥閫夐」锛岃杈撳叆 0 鍒?4 涔嬮棿鐨勬湁鏁堢紪鍙枫€?
        ;;
    esac
  done
fi

# 涓ユ牸鏍￠獙鏈€缁堢増鏈彿鏍煎紡
SEMVER_REGEX="^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$"
if ! echo "$TARGET_VERSION" | grep -Eq "$SEMVER_REGEX"; then
  log_error "鐩爣鐗堟湰鍙?[$TARGET_VERSION] 涓嶇鍚堣涔夊寲鐗堟湰瑙勮寖锛佸繀椤诲舰濡?v1.0.0 鎴?v1.0.0-rc.1"
  exit 1
fi

# 鎷︽埅闈炴硶 SNAPSHOT 鐗堟湰 (Sonatype Central Portal 瀹樻柟閾佸緥锛氫弗绂佸彂甯冨揩鐓х増鏈?
if echo "$TARGET_VERSION" | grep -Eiq "snapshot"; then
  log_error "鐩爣鐗堟湰鍙?[$TARGET_VERSION] 鍖呭惈闈炴硶 SNAPSHOT 鏍囪瘑锛?
  log_error "Maven Central (Sonatype Central Portal) 瀹樻柟绂佹鍙戝竷浠讳綍蹇収鐗堟湰銆?
  log_info "鎻愮ず: 鑻ラ渶瀵瑰鍙戝竷灏濋矞/娴嬭瘯鏋勪欢锛岃浣跨敤 SemVer 璇箟鍖栭鍙戠増鏈紝渚嬪: v${V_MAJOR}.${V_MINOR}.$((V_PATCH + 1))-rc.1 鎴?-beta.1"
  exit 1
fi
log_success "鐩爣鍙戠増鐗堟湰鍙锋牎楠屽悎瑙? $TARGET_VERSION"

# 鏍￠獙鏈湴鍙婅繙绔槸鍚﹀凡瀛樺湪鍚屽悕 Tag
if git rev-parse -q --verify "refs/tags/$TARGET_VERSION" >/dev/null 2>&1; then
  log_error "鏈湴宸插瓨鍦ㄧ浉鍚屾爣绛?[$TARGET_VERSION]锛岀姝㈤噸澶嶅彂鐗堬紒"
  exit 1
fi

if git ls-remote --tags origin "refs/tags/$TARGET_VERSION" 2>/dev/null | grep -q "$TARGET_VERSION"; then
  log_error "杩滅▼浠撳簱宸插瓨鍦ㄧ浉鍚屾爣绛?[$TARGET_VERSION]锛岀姝㈤噸澶嶅彂鐗堬紒"
  exit 1
fi
log_success "鏍囩鍞竴鎬ф鏌ラ€氳繃: $TARGET_VERSION 鏈浘琚娇鐢?

# 鎻愬彇涓嶅甫 v 鐨勭函鐗堟湰鍙蜂緵鎻掓Ы浣跨敤
RAW_VERSION="${TARGET_VERSION#v}"

# ==============================================================================
# 3. 鎷熸墽琛屽姩浣滄眹鎬讳笌鏈€缁堢‘璁?# ==============================================================================
printf "\n%b\n" "${COLOR_BOLD}${COLOR_CYAN}----------------------- 鍙戠増璁″垝鎽樿 -----------------------${COLOR_RESET}"
printf "  鍩哄噯鍘嗗彶鐗堟湰 : %s\n" "$BASE_VERSION"
printf "  鏈鐩爣鐗堟湰 : %b%s%b\n" "${COLOR_BOLD}${COLOR_GREEN}" "$TARGET_VERSION" "${COLOR_RESET}"
printf "  鐩爣 Git 鍒嗘敮: %s\n" "$CURRENT_BRANCH"
if [ "$AHEAD_COUNT" -gt 0 ]; then
  printf "  涓诲共鍓嶇疆鍚屾 : 鎺ㄩ€佹湰鍦拌秴鍓嶆彁浜よ嚦 origin/%s (%s 涓彁浜?\n" "$CURRENT_BRANCH" "$AHEAD_COUNT"
fi
printf "  鎷熸墽琛屽姩浣?  : 1. 鎵ц宸ョ▼鐗堟湰鏇存柊閽╁瓙 (custom_bump_version)\n"
printf "                 2. 鍒涘缓闄勬敞鏍囩: git tag -a %s -m \"Release %s\"\n" "$TARGET_VERSION" "$TARGET_VERSION"
printf "                 3. 鍘熷瓙鍚屾鍒嗘敮涓庢爣绛? git push origin %s %s\n" "$CURRENT_BRANCH" "$TARGET_VERSION"
printf "                 4. 瑙﹀彂 GitHub Actions 鑷姩鍙戠増涓庝骇鐗╂寕杞芥祦姘寸嚎\n"
printf "%b\n\n" "${COLOR_BOLD}${COLOR_CYAN}------------------------------------------------------------${COLOR_RESET}"

if [ "$DRY_RUN" = true ]; then
  log_info "[DRY-RUN] 妯℃嫙鎵ц custom_bump_version $RAW_VERSION..."
  log_info "[DRY-RUN] 妯℃嫙鎵ц git tag -a $TARGET_VERSION -m 'Release $TARGET_VERSION'..."
  log_info "[DRY-RUN] 妯℃嫙鎵ц git push origin $CURRENT_BRANCH $TARGET_VERSION (鍘熷瓙鍚堝苟鎺ㄩ€?..."
  log_success "[DRY-RUN] 婕旂粌瀹屾垚锛佹墍鏈夊彂鐗堥槻鍛嗘鏌ュ潎宸查€氳繃锛屽疄闄呭彂鐗堝皢鎸変笂杩拌鍒掓墽琛屻€?
  exit 0
fi

if [ "$AUTO_CONFIRM" = true ] || [ ! -t 0 ]; then
  log_info "宸叉寚瀹?-y 鎴栧浜庨潪浜や簰鐜锛岃烦杩囦汉宸ョ‘璁わ紝鐩存帴鎵ц鍙戠増娴佺▼銆?
else
  printf "%b纭浠ユ璁″垝绔嬪嵆鍙戠増骞舵帹閫佸埌 GitHub? [Y/n]: %b" "${COLOR_BOLD}" "${COLOR_RESET}"
  read -r CONFIRM_RELEASE
  CONFIRM_RELEASE=$(echo "$CONFIRM_RELEASE" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  case "$CONFIRM_RELEASE" in
    [nN][oO]|[nN])
      log_warn "鍙戠増鎿嶄綔宸茬敱鐢ㄦ埛鍙栨秷锛屾湭鎵ц浠讳綍鏀瑰姩銆?
      exit 0
      ;;
    *)
      ;;
  esac
fi

# ==============================================================================
# 4. 鎵ц鐗堟湰鍙锋洿鏂伴挬瀛愪笌鍙樻洿鎻愪氦
# ==============================================================================
log_info "姝ｅ湪鎵ц宸ョ▼鐗堟湰鍙锋洿鏂伴挬瀛?(custom_bump_version)..."
custom_bump_version "$RAW_VERSION"

# 妫€鏌ラ挬瀛愭槸鍚︿慨鏀逛簡鐗堟湰鏂囦欢
HOOK_CHANGES=$(git status --porcelain)
NEED_PUSH_BRANCH=false
if [ -n "$HOOK_CHANGES" ]; then
  log_info "妫€娴嬪埌鎵╁睍閽╁瓙鏇存柊浜嗗伐绋?POM 涓庢帴鍏ユ枃妗ｏ紝姝ｅ湪绮惧噯鏆傚瓨骞跺垱寤虹増鏈彁浜?.."
  git add pom.xml */pom.xml */*/pom.xml
  [ -f "README.md" ] && git add README.md
  [ -f "ateng-cloud-bom/README.md" ] && git add ateng-cloud-bom/README.md
  git commit -m "chore(release): bump version to $TARGET_VERSION"
  NEED_PUSH_BRANCH=true
elif [ "$AHEAD_COUNT" -gt 0 ]; then
  log_info "妫€娴嬪埌鏈湴鏈?$AHEAD_COUNT 涓秴鍓嶆彁浜わ紝灏嗛殢鍙戠増鏍囩涓€鍚屾帹閫佸埌杩滅..."
  NEED_PUSH_BRANCH=true
else
  log_info "鏈娴嬪埌宸ョ▼鏂囦欢鍙樺姩锛堝綋鍓嶅伐绋嬩繚鎸佹棤鐗堟湰鏂囦欢妯″紡锛夈€?
fi

# ==============================================================================
# 5. 鎵撴爣绛句笌鍘熷瓙鎺ㄩ€?(鍚帹閫佸け璐ヨ嚜鍔ㄥ洖婊?
# ==============================================================================
log_info "姝ｅ湪鍒涘缓闄勬敞 Git Tag: $TARGET_VERSION..."
git tag -a "$TARGET_VERSION" -m "Release $TARGET_VERSION"

if [ "$NEED_PUSH_BRANCH" = true ]; then
  log_info "姝ｅ湪鍘熷瓙鎺ㄩ€佷富骞插彉鏇翠笌鐗堟湰鏍囩 (git push origin $CURRENT_BRANCH $TARGET_VERSION)..."
  if ! git push origin "$CURRENT_BRANCH" "$TARGET_VERSION"; then
    log_error "鍘熷瓙鎺ㄩ€佸垎鏀笌鏍囩鍒拌繙绋嬩粨搴撳け璐ワ紒"
    log_warn "姝ｅ湪鑷姩鍥炴粴鏈湴鏍囩锛屼互闃叉湰鍦拌剰 Tag 闃诲鍚庣画鍙戠増..."
    git tag -d "$TARGET_VERSION" >/dev/null 2>&1 || true
    log_error "鍙戠増涓柇锛氭帹閫佸け璐ワ紝鏈湴鏍囩宸插畨鍏ㄦ竻鐞嗐€?
    exit 1
  fi
  log_success "涓诲共鍙樻洿涓庣増鏈爣绛惧凡鍘熷瓙鍚屾鑷宠繙绔€?
else
  log_info "姝ｅ湪鎺ㄩ€佹爣绛惧埌杩滅▼浠撳簱 (git push origin $TARGET_VERSION)..."
  if ! git push origin "$TARGET_VERSION"; then
    log_error "鎺ㄩ€佹爣绛?$TARGET_VERSION 鍒拌繙绋嬩粨搴撳け璐ワ紒"
    log_warn "姝ｅ湪鑷姩鍥炴粴鏈湴鏍囩锛屼互闃叉湰鍦拌剰 Tag 闃诲鍚庣画鍙戠増..."
    git tag -d "$TARGET_VERSION" >/dev/null 2>&1 || true
    log_error "鍙戠増涓柇锛氭爣绛炬帹閫佸け璐ワ紝鏈湴鏍囩宸插畨鍏ㄦ竻鐞嗐€?
    exit 1
  fi
  log_success "鐗堟湰鏍囩鎺ㄩ€佹垚鍔燂紒"
fi

# 鑾峰彇杩滅浠撳簱鍦板潃鐢熸垚 GitHub Release 缃戦〉鐩磋揪閾炬帴
REMOTE_URL=$(git config --get remote.origin.url || echo "")
REPO_SLUG=""
if [[ "$REMOTE_URL" =~ github\.com[:/]([^/]+)/([^/.]+)(\.git)? ]]; then
  REPO_SLUG="${BASH_REMATCH[1]}/${BASH_REMATCH[2]}"
fi

printf "\n%b\n" "${COLOR_BOLD}${COLOR_GREEN}================================================================${COLOR_RESET}"
printf "%b\n" "${COLOR_BOLD}${COLOR_GREEN}               馃帀 鐗堟湰 $TARGET_VERSION 鍙戠増鎸囦护宸叉垚鍔熻Е鍙戯紒      ${COLOR_RESET}"
printf "%b\n" "${COLOR_BOLD}${COLOR_GREEN}================================================================${COLOR_RESET}"
if [ -n "$REPO_SLUG" ]; then
  printf "GitHub Actions 鍙戠増娴佹按绾垮凡鑷姩寮€濮嬭繍琛岋紝绋嶅悗鍙闂?Release 椤甸潰鏌ョ湅浜х墿:\n"
  printf "馃憠 %bhttps://github.com/%s/releases/tag/%s%b\n\n" "${COLOR_CYAN}" "$REPO_SLUG" "$TARGET_VERSION" "${COLOR_RESET}"
fi
