#!/usr/bin/env bash
# Capturas das telas de entrada (boas-vindas, permissões, entrada) em 360 dp de largura, claro e escuro, pt-BR.
# Roda contra um emulador já ligado (adb). Usado pelo workflow capturas-telas.yml para o "antes/depois" dos PRs
# de interface.
#
#   scripts/capturas-telas.sh <apk> <pasta de saída>
set -euo pipefail
set -x   # rastro no log do CI (o emulador falha sem mensagem)

APK="$1"
SAIDA="$2"
PKG="${PKG:-br.com.grupoivm.nexus}"   # applicationId (o debug não tem sufixo)
mkdir -p "$SAIDA"

# 1080 px a 480 dpi = 360 dp de largura
adb shell wm size 1080x2340
adb shell wm density 480
# "antes" e "depois" vêm de runners diferentes (chave debug diferente): desinstala antes
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$APK" >/dev/null

# coordenadas do centro do elemento pelo resource-id; vazio se não estiver na tela
centro() {
  local b
  adb shell rm -f /sdcard/ui.xml || true
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  b="$(adb exec-out cat /sdcard/ui.xml 2>/dev/null \
       | grep -o "resource-id=\"$PKG:id/$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" \
       | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' || true)"
  [ -n "$b" ] || return 0
  read -r x1 y1 x2 y2 <<< "$(echo "$b" | tr '[],' '   ')"
  echo "$(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))"
}

# espera o elemento aparecer (até 180 s: o SDK é ARM e roda traduzido no emulador x86_64)
esperar() {
  local i xy
  for i in $(seq 1 90); do
    xy="$(centro "$1")"
    [ -n "$xy" ] && { sleep 2; return 0; }
    sleep 2
  done
  echo "sem $1 na tela" >&2
  return 1
}

# toca no elemento pelo resource-id (ex.: skip); falha se não aparecer
tocar() {
  local xy
  esperar "$1" || return 1
  xy="$(centro "$1")"
  [ -n "$xy" ] || return 1
  adb shell input tap $xy || true
  sleep 3
}

# screencap em arquivo + pull (exec-out saiu vazio com o swiftshader); tenta 3 vezes
capturar() {
  local i
  for i in 1 2 3; do
    adb shell rm -f /sdcard/c.png || true
    adb shell screencap -p /sdcard/c.png || echo "screencap falhou ($i)" >&2
    adb pull -q /sdcard/c.png "$SAIDA/$1.png" >/dev/null 2>&1 || true
    [ -s "$SAIDA/$1.png" ] && return 0
    # alternativa: captura pelo console do emulador (grava direto no host)
    adb emu screenrecord screenshot "$SAIDA/$1.png" >/dev/null 2>&1 || true
    [ -s "$SAIDA/$1.png" ] && return 0
    sleep 2
  done
  echo "captura $1 vazia" >&2
  rm -f "$SAIDA/$1.png"
}

for modo in claro escuro; do
  adb shell pm clear "$PKG" >/dev/null
  adb shell cmd locale set-app-locales "$PKG" --locales pt-BR
  if [ "$modo" = escuro ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
  # cada tela pelo id que só ela tem (boas-vindas e permissões têm os dois um "skip")
  esperar dots_indicator && capturar "1-boas-vindas-$modo" && tocar skip || true
  esperar grant_all_permissions && capturar "2-permissoes-$modo" && tocar skip || true
  esperar nexus_scan_qr_code && capturar "3-entrada-$modo" && tocar help || true
  esperar about_section && capturar "4-ajuda-$modo" || true
done
ls -la "$SAIDA"
