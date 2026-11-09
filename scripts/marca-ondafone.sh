#!/usr/bin/env bash
# Gera os vetores da marca Ondafone a partir dos SVGs oficiais do manual (pasta ondafone-marca/svg), sem redesenhar:
#
#   scripts/marca-ondafone.sh [<pasta ondafone-marca>]     (padrão: ~/ondafone-marca)
#
#   ondafone_launcher_foreground  ícone adaptativo e splash: símbolo p/ fundo escuro, 60 dp centralizado nos 108 dp
#                                 (zona segura de 66 dp); o fundo Abismo fica em mipmap-anydpi/ic_launcher*.xml
#   ondafone_launcher_monochrome  ícone temático (Android 13+): símbolo mono
#   ondafone_notificacao          ícone pequeno de notificação, 24 dp: símbolo mono em branco
#   ondafone_logo                 logo com o nome em curvas (boas-vindas, menu lateral, assistente em tablet): fundo claro; drawable-night: fundo escuro
#
# O logo não é GPL (TRADEMARKS.md).
set -euo pipefail

MARCA="${1:-$HOME/ondafone-marca}/svg"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
RES="$RAIZ/app/src/main/res"
V="python3 -I $RAIZ/scripts/marca-vetor.py"
[ -f "$MARCA/ondafone-simbolo-fundo-escuro.svg" ] || { echo "SVGs da marca não encontrados em $MARCA" >&2; exit 1; }
mkdir -p "$RES/drawable-night"

$V "$MARCA/ondafone-simbolo-fundo-escuro.svg" "$RES/drawable/ondafone_launcher_foreground.xml" --caixa 108 --conteudo 60
$V "$MARCA/ondafone-simbolo-mono.svg" "$RES/drawable/ondafone_launcher_monochrome.xml" --caixa 108 --conteudo 60 --cor '#FFFFFF'
$V "$MARCA/ondafone-simbolo-mono.svg" "$RES/drawable/ondafone_notificacao.xml" --dp 24x24 --cor '#FFFFFF'
$V "$MARCA/ondafone-logo-fundo-claro.svg" "$RES/drawable/ondafone_logo.xml" --dp 278.3x50
$V "$MARCA/ondafone-logo-fundo-escuro.svg" "$RES/drawable-night/ondafone_logo.xml" --dp 278.3x50
echo "ok: vetores da marca em $RES/drawable{,-night}"
